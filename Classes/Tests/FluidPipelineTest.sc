FluidPipelineTest1 : UnitTest {
	test_check_classname {
		var result = FluidPipeline.new;
		this.assert(result.class == FluidPipeline);
	}
}


FluidPipelineTester {
	*new {
		^super.new.init();
	}

	init {
		FluidPipelineTest1.run;
	}
}
