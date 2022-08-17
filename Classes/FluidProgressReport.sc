FluidProgressReport {

	var <>name, <>totalTasks, <>doneTasks, clock, reporter;

	*new  { |name = "", totalTasks = 100, clock(TempoClock.default)|
		^super.newCopyArgs(name, totalTasks, 0, clock)
	}

	increment { doneTasks = doneTasks + 1 }

	isDone { ^(doneTasks == totalTasks) }

	getPercent { |round = 1|
		^(doneTasks / totalTasks * 100).round(round)
	}

	printReport { |round = 1|
		"%: %\\% (%/%)".format(name, this.getPercent(round), doneTasks, totalTasks).postln
	}

	startReport { |dt = 1|
		this.stopReport;
		reporter = Task {
			while { doneTasks < totalTasks } {
				this.printReport;
				dt.wait;
			}
		}.play(clock)
	}

	stopReport {
		reporter !? { reporter.stop; reporter = nil }
	}

	clear {
		this.stopReport;
		doneTasks = 0;
		totalTasks = 1;
	}
}
