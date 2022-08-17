FluidPipeline {

	classvar <all;

	var <key, <server, <buffer;
	var <stages, <data;
	var <>running = false;
	var <runningTask;

	// mmm maybe not here, move to SliceFunc
	var <>stageTimeout = 90;

	*initClass {
		all = IdentityDictionary[];
	}

	*register { |key, pipeline|
		if (key.notNil) { all.put(key, pipeline) };
		^pipeline
	}

	*new { |key = nil, stages = #[], run = true, server(Server.default)|
		var pipeline = all.at(key) ?? {
			var newPipeline = super.newCopyArgs(key, server).init;
			key !? { this.register(key, newPipeline) };
			newPipeline
		};

		if (stages.notEmpty) {
			try {
				pipeline.setStages(stages, run)
			} { |err|
				"FluidPipeline: error setting stages:\n %".format(err.errorString).warn
			}
		};

		^pipeline;
	}

	storeArgs { ^[key] }
	printOn { |stream|
		^this.storeOn(stream);
	}

	// TODO: copy data, copy server objects
	*newFrom { |pipeline/*, copyData = false*/|
		/*		if (copyData) {
		^this.new(pipeline.server).copyStages(pipeline).copyData(pipeline)
		} {*/
		^this.new.copyStages(pipeline)
		//}
	}

	init {
		data = IdentityDictionary[];
		stages = List[]; // a list of assoc name -> func
	}

	// DATA

	at { |key| ^this.getSaved(key) }
	getSaved { |key| ^data.at(key) }

	put { |key, val, doWithPrevious| ^this.save(key,val, doWithPrevious)}
	save { |key, value, doWithPrevious|
		if (doWithPrevious.notNil) {
			var previous = this.getSaved(key);
			if (previous.notNil) {
				doWithPrevious.value(previous)
			}
		};

		data.put(key, value);
	}

	clearData {
		this.data.do { |v| if(v.respondsTo(\free)) { v.free }};
		this.data.clear;
	}

	buffer_ { |buf|
		if (buf.server !== server) {
			"[FluidPipeline] new buffer is on a different server. Changing pipeline server to %".format(buf.server);
			server = buf.server;
		};
		buffer = buf
	}

	// RUN

	stageNames { ^this.stages.collect(_.key) }
	orderStages { |keys|
		var notFound = keys.asSet - this.stageNames.asSet;
		var ignored = this.stageNames.asSet - keys.asSet;
		if (notFound.isEmpty.not) {
			"[FluidPipeline:orderStages]: can't reorder because the following stages were not found:".warn;
			notFound.postln;
			^this;
		};
		if (ignored.isEmpty.not) {
			"[FluidPipeline:orderStages]: the following stages will be deleted:".warn;
			ignored.postln;
		};

		stages = stages[keys];
	}

	setStages { |newStages, run = true|
		var changedIndex;
		var error = this.validateStages(newStages);
		error !? {
			"[FluidPipeline:setStages] failed: %".format(error).warn;
		};
		newStages = newStages.asAssociations;
		changedIndex = newStages.detectIndex { |newStage, n|
			var oldStage = stages[n];
			if (oldStage.isNil) { true } {
				this.stageEquals(oldStage, newStage).not
			};
		};
		stages = newStages;
		this.printStages();
		if (run) {
			if (changedIndex.notNil) {
				this.run(this.stageNames[changedIndex])
			} {
				"[FluidPipeline:setStages]: stages didn't change, not running".warn;
			}
		}
	}

	stageEquals { |a, b|
		^ (a.key == b.key) && (a.value.def.sourceCode == b.value.def.sourceCode)
	}

	validateStages { |newStages|
		var names, funcs, namesAreSymbols, funcsAreFuncs;
		if (newStages.isKindOf(SequenceableCollection).not) {
			^"stages need to be a SequenceableCollection";
		};

		if (newStages.size % 2 != 0) {
			^"stages need to contain an even number of elements ([name, func, name, func ...])";
		};

		# names, funcs = newStages.clump(2).flop;
		namesAreSymbols = names.any { |n| n.isSymbol.not }.not;
		funcsAreFuncs = funcs.any { |f| f.isFunction.not }.not;

		if (not(namesAreSymbols && funcsAreFuncs)) {
			^"stages need to contain pairs of symbols and functions ([symbol, function, symbol, function ...])";
		};

		^nil
	}

	copyStages { |pipeline|
		stages = pipeline.stages.collect(_.copy);
	}

	appendStage { |stageName, stageFunc, printStages = true|
		if (stageName.isNil || stageFunc.isNil) {
			"[FluidPipeline:appendStage] you need to provide a stage name and a function".error;
			^this;
		};
		if (this.stageNames.includes(stageName)) {
			"[FluidPipeline:appendStage] stage % already exists".format(stageName).error;
			^this;
		};
		stages = stages ?? { List[] };
		stages = stages.add(stageName -> stageFunc);
		if (printStages) { this.printStages };
	}

	printStages {
		"[FluidPipeline] stages:".postln;
		this.stageNames.do { |stageName| "\t* %".format(stageName).postln }
	}

	isRunning {
		if (runningTask.isNil) {
			^false;
		} {
			^runningTask.isPlaying;
		}
	}

	run { |stageName, argFunc, continue = true|
		var stageIndex;
		this.errorIfNoBuffer;

		if (stageName.isNil) {
			stageIndex = 0;
		} {
			stageIndex = this.stageNames.indexOf(stageName);
			if (stageIndex.isNil) {
				if (argFunc.isNil) {
					"[FluidPipeline] no stage named %".format(stageName).warn;
					^this;
				} {
					"[FluidPipeline] adding stage %".format(stageName).warn;
					this.appendStage(stageName, argFunc);
					stageIndex = stages.size - 1;
				}
			};
		};

		argFunc !? { stages[stageIndex] = argFunc };

		if (this.isRunning) {
			"[FluidPipeline] Already running, please wait".warn;
			^this;
		};

		runningTask = Task {
			block { |break|
				stages[stageIndex..].do { |stage|
					var stageFunc = stage.value;
					stageFunc !? {
						"[FluidPipeline] > running stage %".format(stage.key).postln;

						//try {
						stageFunc.value(this);
						"[FluidPipeline] < done stage %".format(stage.key).postln;
						/*} { |err|
							"[FluidPipeline] error in stage %".format(stage.key).warn;
							err.throw;
							break.value;
						}*/
					};
					if (continue.not) { break.value; }
				};
			};
			runningTask = nil;
		};
		runningTask.play;
	}

	prWasCalledFromRunningTask {
		^(thisThread == runningTask.stream)
	}

	pause {
		if (this.isRunning) {
			if (this.prWasCalledFromRunningTask) {
				"[FluidPipeline] paused".postln;
				\hang.yield;
			} {
				runningTask.pause;
			}
		}
	}

	resume {
		if (this.runningTask.notNil) {
			if (this.runningTask.nextBeat == \hang) {
				"[FluidPipeline] resuming".postln;
				runningTask.clock.sched(0, runningTask);
			} {
				runningTask.resume;
			}
		}
	}

	stop {
		if (this.runningTask.notNil) {
			this.runningTask.stop.clear;
		}
	}

	runOnly { |stageName, argFunc| this.run(stageName, argFunc, false) }

	// UTILS

	errorIfNoBuffer {
		buffer ?? { Error('FluidPipeline: no buffer set!').throw };
	}

	getSliceStats {
		^FluidHelper.listStats(this.getSaved(\sliceList).flop[1] / buffer.sampleRate)
	}

	showSlices {
		var slices = this.getSaved(\sliceList);
		this.errorIfNoBuffer;
		if (slices.isNil) {
			"[fluidPipeline] has no slices to show".warn;
		} {
			var sliceBuf = Buffer.loadCollection(server, slices.flop.first);
			var fwf = FluidWaveform(buffer, sliceBuf);
			{ fwf.parent.onClose_{ sliceBuf.free } }.defer(1);
		}
	}
	showDataset { |datasetName|
		var dataset = this.getSaved(datasetName);
		this.errorIfNoBuffer;
		if (dataset.isNil) {
			"[fluidPipeline] dataset % not found".format(datasetName).warn;
		} {
			var buf = Buffer(server);
			dataset.toBuffer(buf, action: {
				var fwf = FluidWaveform(buffer, features: buf);
				{ fwf.parent.onClose_{ buf.free } }.defer(1);
			})
		}
	}

	makeSlicesLabelSet { |labelsetName = \sliceLabelset|
		var dataset = FluidLabelSet(server);
		var sliceList = this.getSaved(\sliceList);
		var dimensions = sliceList.shape.last;
		var sliceBuffer = if (this.getSaved(\sliceBuffer).notNil) {
			this.getSaved(\sliceBuffer)
		} {
			FluidHelper.await { |done|
				"[SliceLabels] storing in slicesBuffer".postln;
				Buffer.loadCollection(server, sliceList.flatten, dimensions, done);
			}
		};

		"[SliceLabels] loading slicesBuffer to dataset".postln;
		FluidHelper.await { |done|
			dataset.fromBuffer(buffer, action: done)
		};

		"[SliceLabels] saving to labelset: %".format(labelsetName).postln;
		this.save(labelsetName, dataset, { |prev| prev.clear.free });
		"[SliceLabels] done".postln;
	}
}