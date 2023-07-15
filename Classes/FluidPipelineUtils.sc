FluidHelperTimeoutError : Error {
	errorString {
		^"FluidHelper.await: timeout."
	}
}

FluidHelper {

	// waits for a function callback and returns its results
	// the provided function should be a wrapper around an async call
	// *await passes to the wrapper a function to be used as a callback to continue
	// e.g:
	// var res = FluidHelper.await { |done| something.doSomethingAsync(action: done) }
	*await { |asyncFunction, timeout = nil, onTimeout = nil|
		var cond = CondVar(), done = false, res = nil;

		// how to protect this? if errors are thrown in the asyncFunction, we can't catch them
		asyncFunction.value({|...results|
			res = results; done = true;
			cond.signalOne;
		});

		if (timeout.isNil) { cond.wait { done } } { cond.waitFor(timeout) { done } };
		if (done.not) {
			if (onTimeout.isFunction) { ^onTimeout.value } {
				FluidHelperTimeoutError().throw
			}
		};
		^res.unbubble;
	}

	*awaitAll { |maxParallelJobs, asyncFuncs, progressLabel="processing"|
		var pool = Semaphore(maxParallelJobs);
		// this (* 20) is arbitrary, quick and dirty
		var clockWithBigQueue = TempoClock(queueSize: asyncFuncs.size * 20);

		var results = Order[];

		FluidHelper.await { |done|
			var reportProgress = FluidProgressReport(progressLabel, asyncFuncs.size, clockWithBigQueue);
			reportProgress.startReport;
			asyncFuncs.do { |fn, n|
				fork({
					protect {
						pool.wait;
						results[n] = fn.value;
					} {
						var allDone;
						// ">>> done slice %".format(n).postln;
						reportProgress.increment;
						allDone = reportProgress.isDone/*|| (sliceList.size - 1 == n)*/;
						if (allDone) {
							reportProgress.printReport;
							reportProgress.stopReport;
							done.value;
						};
						pool.signal;
					}
				}, clockWithBigQueue)
			}
		};
		clockWithBigQueue.stop.clear;
		^results.asArray;
	}

	// CHAINS

	*dsProcessChain { |srcDataset, dstDataset ...functions|
		var server = srcDataset.server;
		var tempDatasets = functions.drop(-1).collect { FluidDataSet(server) };
		var chainEnv = Environment(parent:currentEnvironment, know: true);
		if (dstDataset.isNil) { dstDataset = FluidDataSet(server) };
		([srcDataset] ++ tempDatasets ++ [dstDataset]).doAdjacentPairs {|src, dst, n|
			FluidHelper.await { |done|
				chainEnv.use {
					~server = server; ~src = src; ~dst = dst; ~done = done;
					functions[n].value(chainEnv);
				}
			};
			if (n > 0) { src.clear.free }
		};
		^dstDataset;
	}

	// given an input dataset and a list of transformers
	// (e.g [FluidNormalize(s), FluidUMAP(s, ...), ...])
	// performs a .fitTransform for each transformer, in chain,
	// creating temporary datasets and freeing them when not needed anymore
	// returns: last dataset processed
	*fitTransformChain { |inputDataset ...transformers|
		var server = inputDataset.server;
		var results = FluidDataSet(server);
		^FluidHelper.dsProcessChain(inputDataset, results,
			*transformers.collect { |transformer|
				{ transformer.fitTransform(~src, ~dst, action: ~done) }
			}
		);
	}

	// chain buffer processes. Example with nested chains, to get clarity-weighted mean pitch:
	// FluidHelper.bufProcessChain(buf, nil)
	// { FluidBufPitch.process(s, ~src, features: ~dst, action: ~done) }
	// {
	//     var pitch = ~src;
	//     FluidHelper.bufProcessChain(~src, ~dst)
	//	   { FluidBufThresh.process(s, ~src, destination: ~dst, action: ~done,
	//         threshold: 0.8, startChan:1, numChans:1) }
	//	   { FluidBufStats.process(s, pitch, stats: ~dst, weights: ~src, action: ~done,
	//         outliersCoeff: 1.5 )}
	//	   { FluidBufSelect.process(s, ~src, ~dst, channels:[1]), action: ~done };
	//     ~done.value(~dst)
	// }

	*bufProcessChain { |srcBuf, destBuf ...functions|
		var server = srcBuf.server;
		var tempBuffers = functions.drop(-1).collect { Buffer(server) };
		var chainEnv = Environment(parent:currentEnvironment, know: true);
		if (destBuf.isNil) { destBuf = Buffer(server) };
		forkIfNeeded {
			([srcBuf] ++ tempBuffers ++ [destBuf]).doAdjacentPairs {|src, dst, n|
				// [n, src, dst].postln;
				FluidHelper.await { |done|
					chainEnv.use {
						~server = server; ~src = src; ~dst = dst; ~done = done;
						functions[n].value(chainEnv);
					}
				};
				if (n > 0) {
					// "freeing %".format(src).postln;
					src.free
				}
			};
		};
		^destBuf;
	}

	// runs a chain of async analysis processes, one after the other,
	// and composes their results in a single one-channel-per-feature buffer.
	// functions passed as arguments look like:
	// { FluidBufPitch.process(features: ~dst, action: ~done) }
	//
	// var analBuf = FluidHelper.bufProcessCompose(sourceBuf)
	// { |feat, done| FluidBufPitch.process(s, ~src, features: ~dst, action: ~done, select:[\pitch]) }
	// { |feat, done| FluidBufLoudness.process(s, ~src, features: ~dst, action: ~done) }
	// // -> analBuf: 2 dimensions = [Pitch, Loudness]
	*bufProcessCompose { |srcBuf, destBuf ...functions|
		var server = srcBuf.server;
		var analBuf = Buffer(server);
		var chainEnv = Environment(parent:currentEnvironment, know: true);
		var startChan = 0;
		if (destBuf.isNil) { destBuf = Buffer(server) };

		forkIfNeeded {
			functions.do { |analFunc|
				// [n, src, dst].postln;
				FluidHelper.await { |done|
					chainEnv.use {
						~server = server; ~src = srcBuf; ~dst = analBuf; ~done = {
							FluidBufCompose.process(server, analBuf,
								destination: destBuf, destStartChan: startChan,
								action: done
							);
						};
						analFunc.value(chainEnv);
					}
				};
				startChan = startChan + analBuf.numChannels;
			};
		};
		^destBuf;
	}

	// analyze slices in parallel:
	// - copy each slice to its own buffer (needed until FluCoMa fixes buffer copying, for now it otherwise copies the whole buffer = too slow, too much mem)
	// - analyze each buf in parallel, up to maxParallelJobs
	// - save results in a new DataSet
	// - return dataset
	// - all tmp buffers are allocated and freed

	// accepts a function to do the actual analysis on a sliceBuf,
	// which is passed the source buf (the slice's audio) and the destination buf (where to save features)
	*analSlices { |srcBuf, sliceList, maxParallelJobs=4, analFunc|
		var server = srcBuf.server;
		var dataset = FluidDataSet(server);

		var asyncFns = sliceList.collect { |slice, n| {
			var featsBuf, sliceBuf;
			// "> processing slice %".format(n).postln;
			featsBuf = Buffer(server);
			sliceBuf = Buffer.alloc(server, slice.last, 1,
				completionMessage: srcBuf.copyMsg(_, 0, *slice)
			);
			server.sync;
			analFunc.value(sliceBuf, featsBuf);
			// ">> anal done slice %".format(n).postln;
			dataset.addPoint(n, featsBuf);
			sliceBuf.free;
			featsBuf.free;
			server.sync;
		}};

		FluidHelper.awaitAll(maxParallelJobs, asyncFns, "analyzing");
		^dataset;
	}

	// analyzes a list of buffers in parallel
	*analBuffers { |srcBufs, maxParallelJobs=4, analFunc|
		var server = srcBufs.first.server;
		var dataset = FluidDataSet(server);

		var asyncFns = srcBufs.collect { |sliceBuf, n| {
			var featsBuf;
			// "> processing slice %".format(n).postln;
			featsBuf = Buffer(server);
			analFunc.value(sliceBuf, featsBuf);
			// ">> anal done slice %".format(n).postln;
			dataset.addPoint(sliceBuf.bufnum, featsBuf);
			featsBuf.free;
			server.sync;
		}};

		FluidHelper.awaitAll(maxParallelJobs, asyncFns, "analyzing");
		^dataset;
	}

	// DATASETS and LISTS

	*getDataSetFromList { |server(Server.default), list, dimensions = 1|
		if (list.rank > 1) {
			dimensions = list.rank;
			list = list.flatten;
		};
		^FluidHelper.await { |done|
			Buffer.loadCollection(server, list, dimensions) { |buf|
				var dataset = FluidDataSet(server);
				dataset.fromBuffer(buf, action: {
					buf.free;
					done.value(dataset);
				});
			}
		}
	}

	*sortedDataSetValuesByKey { |dataset|
		^FluidHelper.await { |done|
			dataset.dump { |dict|
				var sortedKeys = dict["data"].keys.asArray.collect(_.asInteger).sort;
				var sorted = dict["data"].atAll(sortedKeys.collect(_.asString));
				sorted = sorted.collect(_.unbubble);
				done.value(sorted)
			}
		}
	}

	*zipColumns { |datasetA, datasetB, dstDataset|
		var numCols = FluidHelper.await { |done| datasetA.cols(done) };
		if (dstDataset.isNil) { dstDataset = FluidDataSet(datasetA.server)};
		FluidHelper.await { |done|
			var query;
			query = FluidDataSetQuery(datasetA.server).addRange(0, numCols) {
				query.transformJoin(datasetB, datasetA, dstDataset) {
					query.free;
					done.value
				}
			}
		};
		^dstDataset
	}

	// for plotting 1d datasets in 2d
	*addConstantDimension { |dataset, constant = 0.5, dstDataset|
		var numRows = FluidHelper.await { |done| dataset.size(done) };
		var ds_const = FluidHelper.getDataSetFromList(dataset.server, 0.5!numRows);
		FluidHelper.zipColumns(dataset, ds_const, dstDataset);
		ds_const.free;
		^dstDataset;
	}

	// KMEANS

	*getKMeansLabels { |dataset, numClusters, maxIter=100, meansList, saveMeansToDataset|
		var kmeans, labels;
		var server = dataset.server;
		var labelSet = FluidLabelSet(server);
		if (meansList.notNil) {
			var dsMeans = FluidHelper.getDataSetFromList(server, meansList);
			kmeans = FluidKMeans(server, meansList.size, maxIter);
			FluidHelper.await { |done| kmeans.setMeans(dsMeans, done)};
			dsMeans.free;
		} {
			kmeans = FluidKMeans(server, numClusters, maxIter);
		};
		FluidHelper.await { |done| kmeans.fitPredict(dataset, labelSet, done) };
		if (saveMeansToDataset.isKindOf(FluidDataSet)) {
			FluidHelper.await { |done| kmeans.getMeans(saveMeansToDataset, done) };
		};
		kmeans.free;
		labels = FluidHelper.sortedDataSetValuesByKey(labelSet);
		labelSet.free;
		^labels;
	}

	// KMEANS:ELBOW
	// performs kmeans for a selection of numClusters, and returns intertia values
	// usage:
	// FluidHelper.kmeansElbow(s, (2..7), ds_mfcc).plot;
	*kmeansElbow { |numClusters, inputDataset|
		var server = inputDataset.server;
		var elbow = numClusters.collect { |nClusters|
			FluidHelper.await {|done|
				var kmeans, ds;
				kmeans = FluidKMeans(server, nClusters);
				"[FluidHelper: KMeans Elbow] computing for % clusters".format(nClusters).postln;
				ds = FluidHelper.fitTransformChain(inputDataset, kmeans);
				ds.dump {|dict|
					var inertia = 0;
					dict["data"].values.do {|dist|
						inertia = inertia + dist.minItem.squared;
					};
					ds.clear.free;
					kmeans.free;
					done.value(inertia);
				}
			};
		};
		"[FluidHelper: KMeans Elbow] done".postln;
		^elbow;
	}

	// groups labelsList indices by label,
	// assuming labels are Integers starting from 0
	*getIdsByLabel { |labelsList|
		var intLabels = labelsList.collect(_.asInteger);
		var idsByLabel = (intLabels.maxItem + 1).collect { List[] };
		intLabels.do {|l, i| idsByLabel[l].add(i) };
		^idsByLabel
	}

	// Kernel-Density-Estimate
	*kde { |list, bw = 1, numSamples=1000, min, max|
		list = list / bw;
		min = (min ?? { list.minItem });
		max = (max ?? { list.maxItem });
		^ (min, (max-min)/numSamples + min .. max).collect { |x|
			list.inject(0) {|s,xi| s + gaussCurve(x-xi) };
		} / (list.size * bw);
	}

	// PLOTTER

	// needs to be run in a fork
	*getPlotter { |dataset, labelsList(#[]), colors = nil, margin = nil, standalone = true, mouseAction = nil|
		var fp, xmin, ymin, xmax, ymax, cond = CondVar(), datasetDict = nil;
		margin = margin ? 0.1;
		xmin = ymin = 0 - margin;
		xmax = ymax = 1 + margin;

		//"Waiting for dataset".postln;
		dataset.dump { |d| datasetDict = d; cond.signalOne };
		cond.waitFor(10) { datasetDict.notNil };
		datasetDict ?? { Error("Timeout while waiting for dataset").throw };

		fp = FluidPlotter(dict:datasetDict, standalone: standalone,
			xmin: xmin, xmax: xmax, ymin: ymin, ymax: ymax,
			mouseMoveAction: mouseAction
		);

		colors = colors ?? { labelsList.asSet.size.collect { Color.rand } };
		labelsList.do { |v, n| fp.pointColor_(n.asString, colors[v.asInteger] ) };

		^fp;
	}

	*getPlotterKNNAction { |kdtree, buf_2d, slices, sliceAction|
		var previous;
		if (sliceAction.isNil) { ^nil };
		^{ |plotter, x, y|
			buf_2d.setn(0, [x, y]);
			kdtree.kNearest(buf_2d, 1) { |nearest|
				if (previous != nearest) {
					var slice = slices[nearest.asInteger];
					if (slice.notNil) {
						previous = nearest;
						"nearest is % = %".format(nearest, slice).postln;
						sliceAction.value(slice, nearest.asInteger, plotter);
					} {
						"nearest is % = not found".format(nearest).postln;
					}
				}
			}
		}
	}

	*getPlotterKNN { |dataset, kdtree, labelsList(#[]), slices,
		colors = nil, margin = 0.1, standalone = true, sliceAction|

		var buf_2d = Buffer.alloc(dataset.server, 2);

		try {
			var fp = FluidHelper.getPlotter(
				dataset: dataset, labelsList: labelsList,
				colors: colors, margin: margin, standalone: standalone,
				mouseAction: FluidHelper.getPlotterKNNAction(kdtree, buf_2d, slices, sliceAction)
			);

			defer { fp.parent.onClose = { buf_2d.free } }
			^fp;
		} { |err|
			buf_2d.free;
			err.throw;
		};
	}

	// MISC

	*monofy { |buf|
		var server = buf.server;
		var monoBuf = Buffer(server);
		monoBuf.sampleRate_(buf.sampleRate).cache;
		buf.query {
			FluidBufCompose.processBlocking(server, buf, startChan: 0, numChans: 1, gain: (-6).dbamp, destination: monoBuf, destGain: 1);
			FluidBufCompose.processBlocking(server, buf, startChan: 1, numChans: 1, gain: (-6).dbamp, destination: monoBuf, destGain: 1);
		};
		^monoBuf
	}

	*readMonoBuf { |server, path, startFrame = 0, numFrames(-1), gain((-6).dbamp), action|
		var monoBuf = Buffer(server);
		Buffer.read(server, path, startFrame, numFrames, action: { |buf|
			fork {
				"FluidHelper: readMonoBuf %".format(path).postln;
				buf.numChannels.do { |n|
					FluidHelper.await { |done|
						FluidBufCompose.process(server, buf,
							startChan: n, numChans: 1, destination: monoBuf,
							gain: gain, destGain: 1, action: done);
					};
				};
				"FluidHelper: readMonoBuf done %".format(path).postln;
				buf.free;
				action.value(monoBuf);
			}
		});
		^monoBuf
	}

	// returns mean and stdDev
	*listStats { |list|
		var mean = list.mean;
		var stdDev = (list - mean).squared.sum.sqrt / (list.size - 1).max(1);
		^[mean, stdDev];
	}

	*printSliceStats { |sliceList, sampleRate|
		var durs = sliceList.flop[1];
		if (sampleRate.notNil) { durs = durs / sampleRate };
		"[slices] got % slices".format(durs.size).postln;
		"[slices] avg dur = % +- %".format(*FluidHelper.listStats(durs)).postln;
	}

	*showSlices { |buffer, slices, bounds(Rect(0,0,800,400)), printStats = true|
		var histoSteps = 10;
		var gui = View(bounds:bounds), sliceBuf, sliceList, fwf, histo;
		forkIfNeeded {
			if (slices.isKindOf(Buffer)) {
				sliceBuf = slices;
				sliceList = FluidHelper.sliceBufToList(slices, buffer);
			} {
				sliceList = slices;
				sliceBuf = FluidHelper.sliceListToBuf(slices, buffer.server)
			};

			if (printStats) {
				FluidHelper.printSliceStats(sliceList, buffer.sampleRate)
			};

			defer {
				fwf = FluidWaveform(buffer, sliceBuf, standalone: false);
				histo = View();
				gui.layout = VLayout(fwf, histo);
				gui.front;
				{
					var sliceStarts = sliceList.flop[0];
					var histogram = sliceStarts.histo(50);
					Plotter(parent:histo).plotMode_(\bars)
					.domainSpecs_(ControlSpec(0, buffer.numFrames))
					.specs_(ControlSpec(0, histogram.maxItem))
					.setValue(histogram)
				}.value;
			}
		};
		^gui;
	}


	*replaceWindow { |prevWindow, newWindow, fallbackBounds = nil|
		fallbackBounds = fallbackBounds ?? { Rect(200, 200, 800, 800) };
		defer {
			var bounds = try { prevWindow.bounds } ? fallbackBounds;
			newWindow.resizeToBounds(bounds).moveTo(*bounds.origin.asArray);
			prevWindow !? { prevWindow.close };
		}
	}

	*sliceBufToList { |sliceBuf, srcBuf, timeout = 1|
		var slices = FluidHelper.await ({ |done|
			sliceBuf.loadToFloatArray(0, -1, done.value(_));
		}, timeout, onTimeout: {
			Error("FluidHelper.sliceBufToList: timeout waiting for slices").throw
		});

		// format sliceList:
		slices = slices.asArray.collect(_.asInteger);
		// - add zero as first slice point
		if (slices[0] != 0) { slices = [0] ++ slices };
		// - add numFrames as last slice point
		srcBuf !? { slices = (slices ++ srcBuf.numFrames) };
		// - [[startSample, durSamples], ...]
		slices = slices.slide(2).clump(2).collect {|points|
			[points.first, points.last - points.first]
		};

		^slices
	}

	*sliceListToBuf { |sliceList, server|
		^FluidHelper.await { |done|
			Buffer.loadCollection(server, sliceList.flop.first, 1, done)
		}
	}

	// clump bundles without calling .bundleSize,
	// since 'bundleSize' emits a confusing "buffer overflow error"
	// when it's just assessing if the bundle is too long
	// (failing like that it understand it is indeed too long, but we know already)
	/*	*syncLargeBundle { |func|
	var bundle = s.makeBundle(false, func);
	forkIfNeeded {
	var condition = Condition();
	bundle.clumpBundles.do { |item|
	var id = s.addr.makeSyncResponder(condition);
	s.addr.sendBundle(nil, *(item ++ [["/sync", id]]));
	condition.wait;
	};
	}
	}*/
}


/*+ FluidDataSet {
chain { |dstDataset ...processFunctions|
var tempDatasets = functions.drop(-1).collect { FluidDataSet(server) };
var chainEnv = Environment(parent: currentEnvironment, know: true);
if (dstDataset.isNil) { dstDataset = FluidDataSet(this.server) };
([this] ++ tempDatasets ++ [dstDataset]).doAdjacentPairs {|src, dst, n|
FluidHelper.await { |done|
chainEnv.use {
~src = src; ~dst = dst; ~done = done;
functions[n].value(chainEnv)
};
};
if (n > 0) { src.clear.free }
};
^dstDataset;
}
}*/
