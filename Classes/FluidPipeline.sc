// stores results of pipeline computation
FluidPipelineData : EnvironmentRedirect {
	var <server;
	*new { |server(Server.default)|
		^super.new.init(server).know_(true)
	}
	init { |argServer| server = argServer }

	use { |fn|
		var defaultServer = Server.default;
		if (defaultServer != server) {
			var res;
			protect {
				Server.default = server;
				res = super.use(fn);
			} {
				Server.default = defaultServer;
			};
			^res
		} {
			^super.use(fn)
		}
	}

	// automatically free old resources (if freeable)
	put { |key, value|
		if (this.at(key).respondsTo(\free)) {
			this.at(key).free
		};
		super.put(key,value);
	}

	// automatically free buffers and datasets
	// or anything else that is freeable
	clear {
		var freeableResources = this.select(_.respondsTo(\free));
		if (freeableResources.size > 0) {
			server.makeBundle(0) { freeableResources.do(_.free) }
		};
		super.clear
	}

	getType { |type|
		^this.select(_.isKindOf(type))
	}
	getBuffers { ^this.getType(Buffer) }
	getDataSets { ^this.getType(FluidDataSet) }
	getViews { ^this.getType(View) }

	// File I/O

	write { |filePath|
		var data = IdentityDictionary[];
		this.keysValuesDo { |k, v|
			if (v.isKindOf(FluidDataObject) or: v.isKindOf(Buffer)) {
				// object needs to be written to a file
				var objPath = PathName("%.%".format(k, v.class));
				v.write((filePath.dirname +/+ objPath).fullPath);
				// store Association: Class -> PathName
				data.put(k, v.class -> objPath);
			} {
				data.put(k, v)
			}
		};
		data.writeArchive(filePath)
	}

	read { |filePath|
		var archive = Object.readArchive(filePath);
		var data = IdentityDictionary[];

		archive.keysValuesDo { |k, v|
			var value = if (v.isKindOf(Association) and: {
				v.key.isKindOf(Class) and: v.value.isKindOf(PathName)
			}) {
				// object was saved to file and stored as class -> path
				var class = v.key,
				valPath = (filePath.dirname +/+ v.value).fullPath;
				case
				{ class === Buffer } {
					Buffer.read(server, valPath)
				}
				{ class.isKindOfClass(FluidDataObject) } {
					class.new(server).read(valPath)
				} {
					Error("Can't load '%': unsupported type '%'"
						.format(valPath, class)).throw;
				}
			} {
				v
			};
			data.put(k, value);
		};

		this.clear;
		data.keysValuesDo(this.put(_,_));
	}



	// DATA: views
	/*saveView { |key, view, closeOld = true|
	var old = views[key];
	if (old.notNil && closeOld) {
	var fallbackBounds = if (view.bounds != Rect(0,0,0,0)) {view.bounds} {nil};
	FluidHelper.replaceWindow(old, view, fallbackBounds)
	};
	views.put(key, view);
	}*/

}

FluidPipelineRun {
	var <inputs, <pipeline, <server, <data;
	var <runningTask;

	*new { |inputs, pipeline, register=false, server(Server.default)|
		^super.newCopyArgs(inputs, pipeline, server).init(register);
	}

	init { |register|
		data = FluidPipelineData();
		data.inputs = inputs;
		ServerBoot.add(this, server);
		if (register) { this.runOnChange(true) };
	}

	// NOTE: registering for auto-run on changes stops garbage collection
	// that's why we default to not registering
	// once you register, be careful not to loose reference to this obj
	runOnChange { |run|
		case
		{ run.isNil } {
			^pipeline.hasListener(this)
		}
		{ run === true } {
			pipeline.addListener(this) { |stage| this.run(stage, true) };
		}
		{ run === false } {
			pipeline.removeListener(this);
		}
	}

	doOnServerBoot {
		data.clear;
	}

	// DATA

	at { |key| ^data.at(key) }
	put { |key, val| ^data.put(key,val) }

	clearData { data.clear }

	// RUNNING

	run { |stageName, continue = true|
		var stageIndex = if (stageName.isNil) { 0 } {
			pipeline.stageNames.indexOf(stageName) ?? {
				"[FluidPipeline] ERROR: no stage named %".format(stageName).warn;
				^this;
			}
		};

		server.ifNotRunning {
			"[FluidPipeline] Server '%' not running".format(server).warn;
			^this;
		};

		if (this.isRunning) {
			"[FluidPipeline] Already running, please wait".warn;
			^this;
		};

		runningTask = Task {
			block { |break|
				pipeline.stages[stageIndex..].do { |stage|
					var stageFunc = stage.value;
					stageFunc !? {
						"[FluidPipeline] > running stage %".format(stage.key).postln;

						//try {
						data.use {
							stageFunc.value(this)
						};
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

	runOnly { |stageName| this.run(stageName, false) }

	isRunning {
		if (runningTask.isNil) {
			^false;
		} {
			^runningTask.isPlaying;
		}
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

	// UTILS

	checkInputs { |checksDict|
		checksDict.keysValuesDo { |inputName, checkFn|
			if (checkFn.isKindOf(Association)) {
				var default = inputs.use { checkFn.key.value };
				inputs[inputName] = inputs[inputName] ? default;
				checkFn = checkFn.value;
			};
			if(inputs.includesKey(inputName).not) {
				Error(
					"FluidPipeline.checkInputs: '%' not found".format(inputName)
				).throw
			};
			if (checkFn.isKindOf(Class)) {
				var class = checkFn;
				checkFn = _.isKindOf(class)
			};
			if (checkFn.value(inputs[inputName]).not) {
				Error(
					"FluidPipeline.checkInputs: '%' not valid".format(inputName)
				).throw;
			}
		}
	}

	getSliceStats {
		^FluidHelper.listStats(this.getSaved(\sliceList).flop[1] / this.buffer.sampleRate)
	}

	showSlices {
		var slices = this.getSaved(\sliceList);
		// this.errorIfNoBuffer;
		if (slices.isNil) {
			"[fluidPipeline] has no slices to show".warn;
		} {
			Buffer.loadCollection(server, slices.flop.first, action: { |sliceBuf|
				defer {
					FluidWaveform(inputs[\buffer], sliceBuf).parent.onClose_{
						sliceBuf.free
					};
				};
			});

		}
	}
	showDataset { |datasetName|
		var dataset = this.getSaved(datasetName);
		// this.errorIfNoBuffer;
		if (dataset.isNil) {
			"[fluidPipeline] dataset % not found".format(datasetName).warn;
		} {
			var buf = Buffer(server);
			dataset.toBuffer(buf, action: {
				var fwf = FluidWaveform(this.buffer, features: buf);
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
			dataset.fromBuffer(this.buffer, action: done)
		};

		"[SliceLabels] saving to labelset: %".format(labelsetName).postln;
		this.save(labelsetName, dataset, { |prev| prev.clear.free });
		"[SliceLabels] done".postln;
	}
}

/* class FluidPipeline
* multi-stage pipeline: basically a sequence of async functions
* FEATURES:
* - definition like Ndefs: global store, updatable,
* - runnable as a "pure async function": outputs = run(inputs)
* - outputs a FluidPipelineRun object to keep state
* - supports auto-update of registered FluidPipelineRun objects on def change
*
* define (and update) like this:

FluidPipeline(\name, [
slice: { |data| data[\slices] = doSomething(data.inputs) },
anal: { |data| data[\anal] = asyncAnalysis(data[\slices]) }
])

* run like this:

d = FluidPipeline(\name).run(inputs, register: false)

* run returns a FluidPipelineRun object, to store inputs, results and running task
* object can be updated automatically when def changes (with d.runOnChanges(true))
*/
FluidPipeline {

	classvar <all;

	var <key, <stages, <listeners;

	*initClass {
		all = IdentityDictionary[];
	}

	*registerNew { |key|
		var pipeline = super.newCopyArgs(key).init;
		all.put(key, pipeline);
		^pipeline;
	}

	*new { |key = nil, stages = #[]|
		var pipeline = case
		{ key.isNil } { super.newCopyArgs(key).init } // no key, make new obj
		{ all.includesKey(key) } { all.at(key) } // key found
		{ stages.notEmpty } { this.registerNew(key) } // key not found, defining stages
		{ ^nil }; // key not found, not defining

		if (stages.notEmpty) {
			try {
				pipeline.setStages(stages)
			} { |err|
				"FluidPipeline: error setting stages:\n %".format(err.errorString).warn
			};
		};

		^pipeline;
	}

	*newFrom { |pipeline|
		^this.new.prCopyStages(pipeline)
	}

	init {
		stages = List[]; // a list of assoc name -> func
		listeners = Set[];
	}

	// STAGES

	stageNames { ^this.stages.collect(_.key) }

	setStages { |newStages, printStages = true|
		var changedIndex;
		var error = this.prValidateStages(newStages);
		error !? {
			"[FluidPipeline:setStages] failed: %".format(error).warn;
		};
		newStages = newStages.asAssociations(List);
		changedIndex = newStages.detectIndex { |newStage, n|
			var oldStage = stages[n];
			if (oldStage.isNil) { true } {
				this.prStageEquals(oldStage, newStage).not
			};
		};
		stages = newStages.postln;
		if (printStages) { this.printStages };
		changedIndex !? {
			this.notifyListeners(changedIndex)
		};
	}

	prStageEquals { |a, b|
		^ (a.key == b.key) && (a.value.def.sourceCode == b.value.def.sourceCode)
	}

	prValidateStages { |newStages|
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

	prCopyStages { |pipeline|
		stages = pipeline.stages.collect(_.copy);
	}

	// LISTENERS:
	// manage registrations for FluidPipelineRun objects to auto-update on changes

	notifyListeners { |changedIndex=0|
		var stageName = this.stageNames[changedIndex];
		"[FluidPipeline] changed: %".format(stageName).postln;
		NotificationCenter.notify(this, \stageChanged, stageName);
	}

	addListener { |listener, fn|
		NotificationCenter.register(this, \stageChanged, listener, fn);
		listeners.add(listener);
	}
	removeListener { |listener|
		NotificationCenter.unregister(this, \stageChanged, listener);
		listeners.remove(listener)
	}
	removeAllListeners {
		listeners.do { |l| NotificationCenter.unregister(this, \stageChanged, l)};
		listeners.clear;
	}
	hasListener { |listener|
		^listeners.includes(listener)
	}

	// RUN

	run { |inputs, register=false, server(Server.default)|
		var data = FluidPipelineRun(inputs, this, register, server);
		data.run;
		^data;
	}

	// PRINT

	printStages {
		"[FluidPipeline] stages:".postln;
		this.stageNames.do { |stageName| "\t* %".format(stageName).postln }
	}

	storeArgs { ^[key] }
	printOn { |stream|
		^this.storeOn(stream);
	}
}