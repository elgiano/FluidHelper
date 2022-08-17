FluidPipelineSlice {
	*new { |sliceFunc|
		sliceFunc ?? {
			Error('FluidPipelineSlice: you need to provide a slicing function').throw;
		};
		^ { |pipeline|
			var slices = nil, sliceBuf;

			pipeline.errorIfNoBuffer;
			"[FluidPipeline] slicing buf % (%)"
			.format(pipeline.buffer.bufnum, pipeline.buffer.path).postln;

			// slice
			sliceBuf = Buffer(pipeline.server);
			sliceFunc.value(pipeline, sliceBuf);

			slices = FluidHelper.await ({ |done|
				sliceBuf.loadToFloatArray(0, -1, { |fa| done.value(fa) });
			}, pipeline.stageTimeout, onTimeout: {
				Error("FluidPipelineSlice: timeout waiting for slices").throw
			});

			// format sliceList
			slices = slices.asArray.collect(_.asInteger);
			if (slices[0] != 0) { slices = [0] ++ slices };
			slices = (slices ++ pipeline.buffer.numFrames).slide(2).clump(2)
			.collect {|points|
				[points.first, points.last - points.first]
			};

			// save
			pipeline.save(\sliceList, slices);
			pipeline.save(\sliceBuffer, sliceBuf, _.free);

			"[FluidPipeline] got % slices".format(slices.size).postln;
			"[FluidPipeline] avg dur = % +- %".format(*pipeline.getSliceStats).postln;
		}
	}
}

FluidPipelineAnal {
	*new { |datasetName, sliceAnalFunc|

		^{ |pipeline|

			var sliceList = pipeline.getSaved(\sliceList);
			var dataset = FluidDataSet(pipeline.server);
			var reportProgress = FluidProgressReport("analyzing", sliceList.size);
			"[FluidPipeline] analyzing buf % (%)"
			.format(pipeline.buffer.bufnum, pipeline.buffer.path).postln;

			"[FluidPipeline] anal start".postln;
			reportProgress.startReport;
			sliceList.do { |slice, n|
				var featsBuf = Buffer(pipeline.server);
				sliceAnalFunc.value(pipeline, slice, featsBuf);
				dataset.addPoint(n, featsBuf);
				featsBuf.free;
				pipeline.server.sync;
				reportProgress.increment;
			};
			reportProgress.stopReport;
			"[FluidPipeline] saving to dataset: %".format(datasetName).postln;
			pipeline.save(datasetName, dataset, { |prev| prev.clear.free });
		}
	}
}

FluidPipelineAnalParallel {
	*new { |maxParallelJobs = 4, datasetName = "analDataset", sliceAnalFunc|
		sliceAnalFunc ?? {
			Error("%: you need to provide an analysis function".format(this.class)).throw
		};

		^ { |pipeline|
			var sliceList = pipeline.getSaved(\sliceList);
			var pool = Semaphore(maxParallelJobs);
			var dataset = FluidDataSet(pipeline.server);
			// this (* 20) is arbitrary, quick and dirty
			var clockWithBigQueue = TempoClock(queueSize: sliceList.size * 20);
			"[FluidPipeline] analyzing buf % (%)"
			.format(pipeline.buffer.bufnum, pipeline.buffer.path).postln;

			FluidHelper.await { |done|
				var reportProgress = FluidProgressReport("analyzing", sliceList.size, clockWithBigQueue);
				reportProgress.startReport;
				sliceList.do { |slice, n|
					fork({
						protect {
							var featsBuf, sliceBuf;
							pool.wait;
							// "> processing slice %".format(n).postln;
							featsBuf = Buffer(pipeline.server);
							sliceBuf = Buffer.alloc(pipeline.server, slice.last, 1,
								completionMessage: pipeline.buffer.copyMsg(_, 0, *slice)
							);
							pipeline.server.sync;
							sliceAnalFunc.value(pipeline, sliceBuf, featsBuf);
							// ">> anal done slice %".format(n).postln;
							dataset.addPoint(n, featsBuf);
							sliceBuf.free;
							featsBuf.free;
							pipeline.server.sync;
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
			"[FluidPipeline] saving to dataset: %".format(datasetName).postln;
			pipeline.save(datasetName, dataset, { |prev| prev.clear.free });
		}
	}
}