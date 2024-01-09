+ FluidBufChroma {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, numChroma = 12, ref = 440, normalize = 0, minFreq = 0, maxFreq = -1, windowSize = 1024, hopSize = -1, fftSize = -1, padding = 1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, numChroma, ref, normalize, minFreq, maxFreq, windowSize, hopSize, fftSize, padding, freeWhenDone, ~done);
	}
}
+ FluidBufPitch {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, select, algorithm = 2, minFreq = 20, maxFreq = 10000, unit = 0, windowSize = 1024, hopSize = -1, fftSize = -1, padding = 1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, select, algorithm, minFreq, maxFreq, unit, windowSize, hopSize, fftSize, padding, freeWhenDone, ~done);
	}
}
+ FluidBufLoudness {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, select, kWeighting = 1, truePeak = 1, windowSize = 1024, hopSize = 512, padding = 1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, select, kWeighting, truePeak, windowSize, hopSize, padding, freeWhenDone, ~done);
	}
}
+ FluidBufOnsetSlice {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, metric = 0, threshold = 0.5, minSliceLength = 2, filterSize = 5, frameDelta = 0, windowSize = 1024, hopSize = -1, fftSize = -1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, metric, threshold, minSliceLength, filterSize, frameDelta, windowSize, hopSize, fftSize, freeWhenDone, ~done);
	}
}
+ FluidBufSelectEvery {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, frameHop = 1, chanHop = 1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, frameHop, chanHop, freeWhenDone, ~done);
	}
}
+ FluidBufAmpFeature {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, fastRampUp = 1, fastRampDown = 1, slowRampUp = 100, slowRampDown = 100, floor = -144, highPassFreq = 85, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, fastRampUp, fastRampDown, slowRampUp, slowRampDown, floor, highPassFreq, freeWhenDone, ~done);
	}
}
+ FluidBufNoveltyFeature {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, algorithm = 0, kernelSize = 3, filterSize = 1, windowSize = 1024, hopSize = -1, fftSize = -1, padding = 1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, algorithm, kernelSize, filterSize, windowSize, hopSize, fftSize, padding, freeWhenDone, ~done);
	}
}
+ FluidBufMelBands {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, numBands = 40, minFreq = 20, maxFreq = 20000, normalize = 1, scale = 0, windowSize = 1024, hopSize = -1, fftSize = -1, padding = 1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, numBands, minFreq, maxFreq, normalize, scale, windowSize, hopSize, fftSize, padding, freeWhenDone, ~done);
	}
}
+ FluidBufScale {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, inputLow = 0, inputHigh = 1, outputLow = 0, outputHigh = 1, clipping = 0, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, inputLow, inputHigh, outputLow, outputHigh, clipping, freeWhenDone, ~done);
	}
}
+ FluidBufOnsetFeature {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, metric = 0, filterSize = 5, frameDelta = 0, windowSize = 1024, hopSize = -1, fftSize = -1, padding = 1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, metric, filterSize, frameDelta, windowSize, hopSize, fftSize, padding, freeWhenDone, ~done);
	}
}
+ FluidBufAmpSlice {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, fastRampUp = 1, fastRampDown = 1, slowRampUp = 100, slowRampDown = 100, onThreshold = -144, offThreshold = -144, floor = -144, minSliceLength = 2, highPassFreq = 85, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, fastRampUp, fastRampDown, slowRampUp, slowRampDown, onThreshold, offThreshold, floor, minSliceLength, highPassFreq, freeWhenDone, ~done);
	}
}
+ FluidBufAmpGate {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, rampUp = 10, rampDown = 10, onThreshold = -90, offThreshold = -90, minSliceLength = 1, minSilenceLength = 1, minLengthAbove = 1, minLengthBelow = 1, lookBack = 0, lookAhead = 0, highPassFreq = 85, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, rampUp, rampDown, onThreshold, offThreshold, minSliceLength, minSilenceLength, minLengthAbove, minLengthBelow, lookBack, lookAhead, highPassFreq, freeWhenDone, ~done);
	}
}
+ FluidBufCompose {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, gain = 1, destStartFrame = 0, destStartChan = 0, destGain = 0, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, gain, ~dst, destStartFrame, destStartChan, destGain, freeWhenDone, ~done);
	}
}
+ FluidBufSpectralShape {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, select, minFreq = 0, maxFreq = -1, rolloffPercent = 95, unit = 0, power = 0, windowSize = 1024, hopSize = -1, fftSize = -1, padding = 1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, select, minFreq, maxFreq, rolloffPercent, unit, power, windowSize, hopSize, fftSize, padding, freeWhenDone, ~done);
	}
}
+ FluidBufNoveltySlice {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, algorithm = 0, kernelSize = 3, threshold = 0.5, filterSize = 1, minSliceLength = 2, windowSize = 1024, hopSize = -1, fftSize = -1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, algorithm, kernelSize, threshold, filterSize, minSliceLength, windowSize, hopSize, fftSize, freeWhenDone, ~done);
	}
}
+ FluidBufTransientSlice {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, order = 20, blockSize = 256, padSize = 128, skew = 0, threshFwd = 2, threshBack = 1.1, windowSize = 14, clumpLength = 25, minSliceLength = 1000, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, order, blockSize, padSize, skew, threshFwd, threshBack, windowSize, clumpLength, minSliceLength, freeWhenDone, ~done);
	}
}
+ FluidBufMFCC {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, numCoeffs = 13, numBands = 40, startCoeff = 0, minFreq = 20, maxFreq = 20000, windowSize = 1024, hopSize = -1, fftSize = -1, padding = 1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, numCoeffs, numBands, startCoeff, minFreq, maxFreq, windowSize, hopSize, fftSize, padding, freeWhenDone, ~done);
	}
}
+ FluidBufSelect {
	*chain { |destination, channels = #[ -1 ], freeWhenDone = true|
		^this.process(~src.server, ~src, destination, ~dst, channels, freeWhenDone, ~done);
	}
}
+ FluidBufStats {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, stats, select, numDerivs = 0, low = 0, middle = 50, high = 100, outliersCutoff = -1, weights, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, select, numDerivs, low, middle, high, outliersCutoff, weights, freeWhenDone, ~done);
	}
}
+ FluidBufThresh {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, threshold = 0, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, threshold, freeWhenDone, ~done);
	}
}
+ FluidBufFlatten {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, axis = 1, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, axis, freeWhenDone, ~done);
	}
}
+ FluidBufAmpFeature2 {
	*chain { |startFrame = 0, numFrames = -1, startChan = 0, numChans = -1, fastRampUp = 1, fastRampDown = 1, slowRampUp = 100, slowRampDown = 100, floor = -144, highPassFreq = 85, freeWhenDone = true|
		^this.process(~src.server, ~src, startFrame, numFrames, startChan, numChans, ~dst, fastRampUp, fastRampDown, slowRampUp, slowRampDown, floor, highPassFreq, freeWhenDone, ~done);
	}
}