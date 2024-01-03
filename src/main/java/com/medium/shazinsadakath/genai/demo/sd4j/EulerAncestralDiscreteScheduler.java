/*
 * Copyright (c) 2023 Oracle and/or its affiliates.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.medium.shazinsadakath.genai.demo.sd4j;

import java.util.SplittableRandom;
import java.util.logging.Logger;

/**
 * An Euler Ancestral scheduler.
 * <p>
 * Uses a fresh noise sample at each step which injects more variability into the diffusion process.
 * <p>
 * Scheduler implementations are stateful and not thread-safe.
 */
public final class EulerAncestralDiscreteScheduler implements Scheduler {
    private static final Logger logger = Logger.getLogger(EulerAncestralDiscreteScheduler.class.getName());

    private final int numTrainTimesteps;
    private final float[] alphasCumulativeProducts;
    private final float[] initialVariance;
    private float initNoiseSigma;

    private float[] sigmas = null;
    private int[] timesteps = null;

    private final SplittableRandom rng;

    /**
     * Creates an Euler ancestral scheduler with the default parameters:
     * train timesteps = 1000, beta start = 0.00085f, beta end = 0.012f, Scaled Linear schedule.
     * @param seed The RNG seed for generating the per step noise.
     */
    public EulerAncestralDiscreteScheduler(long seed) {
        this(1000, 0.00085f, 0.012f, ScheduleType.SCALED_LINEAR, seed);
    }

    /**
     * Creates an Euler ancestral scheduler with the specified parameters.
     * @param numTrainTimesteps The number of training time diffusion steps.
     * @param betaStart The start value of the noise level.
     * @param betaEnd The end value of the noise level.
     * @param betaSchedule The noise schedule.
     * @param seed The RNG seed for generating the per step noise.
     */
    public EulerAncestralDiscreteScheduler(int numTrainTimesteps, float betaStart, float betaEnd, ScheduleType betaSchedule, long seed) {
        this.numTrainTimesteps = numTrainTimesteps;

        rng = new SplittableRandom(seed);

        float[] betas = switch (betaSchedule) {
            case LINEAR -> MathUtils.linspace(betaStart, betaEnd, numTrainTimesteps, true);
            case SCALED_LINEAR -> {
                var start = (float) Math.sqrt(betaStart);
                var end = (float) Math.sqrt(betaEnd);
                var tmp = MathUtils.linspace(start, end, numTrainTimesteps, true);
                for (int i = 0; i < tmp.length; i++) {
                    tmp[i] = tmp[i] * tmp[i];
                }
                yield tmp;
            }
        };

        var alphas = new float[betas.length];
        this.alphasCumulativeProducts = new float[alphas.length];
        var cumProd = 1.0f;
        for (int i = 0; i < alphas.length; i++) {
            alphas[i] = 1 - betas[i];
            cumProd *= alphas[i];
            alphasCumulativeProducts[i] = cumProd;
        }

        // Create sigmas as a list and reverse it
        float curMax = Float.NEGATIVE_INFINITY;
        this.initialVariance = new float[alphasCumulativeProducts.length];
        for (int i = 0; i < alphasCumulativeProducts.length; i++) {
            float curVal = alphasCumulativeProducts[(alphasCumulativeProducts.length-1) - i];
            float newVal = (float) Math.sqrt((1-curVal) / curVal);
            initialVariance[i] = newVal;
            if (newVal > curMax) {
                curMax = newVal;
            }
        }

        // standard deviation of the initial noise distribution
        this.initNoiseSigma = curMax;
    }

    @Override
    public float getInitialNoiseSigma() {
        return initNoiseSigma;
    }

    /**
     * Reinitializes the scheduler with the specified number of inference steps.
     * @param numInferenceSteps The number of inference steps.
     * @return The new timesteps.
     */
    @Override
    public int[] setTimesteps(int numInferenceSteps) {
        float start = 0;
        float stop = numTrainTimesteps - 1;
        float[] timesteps = MathUtils.linspace(start, stop, numInferenceSteps, true);

        this.timesteps = new int[timesteps.length];
        for (int i = 0; i < timesteps.length; i++) {
            this.timesteps[i] = (int) timesteps[(timesteps.length-1)-i];
        }

        var range = MathUtils.arange(0, initialVariance.length, 1.0f);
        this.sigmas = MathUtils.interpolate(timesteps, range, initialVariance);
        float curMax = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < sigmas.length; i++) {
            if (curMax < sigmas[i]) {
                curMax = sigmas[i];
            }
        }
        this.initNoiseSigma = curMax;
        return this.timesteps;
    }

    @Override
    public void scaleInPlace(FloatTensor sample, int timestep) {
        // Get step index of timestep from TimeSteps
        int stepIndex = MathUtils.findIdx(this.timesteps, timestep);
        // Get sigma at stepIndex
        var sigma = this.sigmas[stepIndex];
        sigma = (float)Math.sqrt((sigma*sigma) + 1);

        sample.scale(1/sigma);
    }

    @Override
    public FloatTensor step(FloatTensor modelOutput, int timestep, FloatTensor sample, int order) {
        int stepIndex = MathUtils.findIdx(this.timesteps,timestep);
        final float sigma = this.sigmas[stepIndex];

        // 1. compute predicted original sample (x_0) from sigma-scaled predicted noise
        FloatTensor predOriginalSample = new FloatTensor(modelOutput.shape);
        for (int i = 0; i < modelOutput.numElements; i++) {
            predOriginalSample.buffer.put(i, sample.buffer.get(i) - (sigma * modelOutput.buffer.get(i)));
        }

        final float sigmaTo = this.sigmas[stepIndex + 1];

        var sigmaSq = sigma*sigma;
        var sigmaToSq = sigmaTo*sigmaTo;
        var sigmaFromLessSigmaTo = sigmaSq - sigmaToSq;
        var sigmaUpResult = (sigmaToSq * sigmaFromLessSigmaTo) / sigmaSq;
        float sigmaUp = (float) (sigmaUpResult < 0 ? -Math.sqrt(Math.abs(sigmaUpResult)) : Math.sqrt(sigmaUpResult));

        var sigmaDownResult = sigmaToSq - (sigmaUp*sigmaUp);
        var sigmaDown = (float) (sigmaDownResult < 0 ? -Math.sqrt(Math.abs(sigmaDownResult)) : Math.sqrt(sigmaDownResult));

        // 2. Convert to an ODE derivative
        var derivative = new FloatTensor(sample.shape);
        for (int i = 0; i < modelOutput.numElements; i++) {
            derivative.buffer.put(i, (sample.buffer.get(i) - predOriginalSample.buffer.get(i)) / sigma);
        }

        float dt = sigmaDown - sigma;

        var prevSample = new FloatTensor(sample.shape);
        for (int i = 0; i < modelOutput.numElements; i++) {
            prevSample.buffer.put(i, sample.buffer.get(i) + (derivative.buffer.get(i) * dt));
        }

        FloatTensor noiseTensor = generateNoiseTensor(sample.shape);

        for (int i = 0; i < modelOutput.numElements; i++) {
            prevSample.buffer.put(i, prevSample.buffer.get(i) + (noiseTensor.buffer.get(i) * sigmaUp));
        }

        return prevSample;
    }

    private FloatTensor generateNoiseTensor(long[] shape) {
        var latents = new FloatTensor(shape);

        for(int i = 0; i < latents.buffer.capacity(); i++) {
            double stdNormal = rng.nextGaussian(0, 1);
            latents.buffer.put(i, (float) stdNormal);
        }

        return latents;
    }

}