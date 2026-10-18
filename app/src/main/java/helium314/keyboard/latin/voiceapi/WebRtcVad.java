/*
 * SPDX-License-Identifier: BSD-3-Clause AND GPL-3.0-only
 *
 * A Java port of the voice activity detector (VAD) of the WebRTC project: common_audio/vad (vad_core.c,
 * vad_filterbank.c, vad_gmm.c, vad_sp.c, webrtc_vad.c) and the helpers it needs from common_audio/signal_processing
 * (WebRtcSpl_DivW32W16, WebRtcSpl_Energy, WebRtcSpl_GetScalingSquare, WebRtcSpl_NormW32, WebRtcSpl_NormU32), taken
 * from the copy in py-webrtcvad (https://github.com/wiseman/py-webrtcvad, cbits/webrtc). The fixed-point arithmetic
 * is kept exactly: every int16_t of the C code is an int truncated with (short). Only 8 and 16 kHz, frames of 10, 20
 * or 30 ms. Stage 5g-6 of AI Keyboard (docs: STATUS.md); the license of WebRTC is in NOTICE.md.
 *
 * Copyright (c) 2011, The WebRTC project authors. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *   * Redistributions of source code must retain the above copyright notice, this list of conditions and the
 *     following disclaimer.
 *   * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *     following disclaimer in the documentation and/or other materials provided with the distribution.
 *   * Neither the name of Google nor the names of its contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package helium314.keyboard.latin.voiceapi;

public final class WebRtcVad {
    private static final int kNumChannels = 6;
    private static final int kNumGaussians = 2;
    private static final int kTableSize = kNumChannels * kNumGaussians;
    private static final int kMinEnergy = 10;

    // ---- vad_core.c ----
    private static final short[] kSpectrumWeight = { 6, 8, 10, 12, 14, 16 };
    private static final int kNoiseUpdateConst = 655;
    private static final int kSpeechUpdateConst = 6554;
    private static final int kBackEta = 154;
    private static final short[] kMinimumDifference = { 544, 544, 576, 576, 576, 576 };
    private static final short[] kMaximumSpeech = { 11392, 11392, 11520, 11520, 11520, 11520 };
    private static final short[] kMinimumMean = { 640, 768 };
    private static final short[] kMaximumNoise = { 9216, 9088, 8960, 8832, 8704, 8576 };
    private static final short[] kNoiseDataWeights = { 34, 62, 72, 66, 53, 25, 94, 66, 56, 62, 75, 103 };
    private static final short[] kSpeechDataWeights = { 48, 82, 45, 87, 50, 47, 80, 46, 83, 41, 78, 81 };
    private static final short[] kNoiseDataMeans = { 6738, 4892, 7065, 6715, 6771, 3369, 7646, 3863, 7820, 7266, 5020, 4362 };
    private static final short[] kSpeechDataMeans = { 8306, 10085, 10078, 11823, 11843, 6309, 9473, 9571, 10879, 7581, 8180, 7483 };
    private static final short[] kNoiseDataStds = { 378, 1064, 493, 582, 688, 593, 474, 697, 475, 688, 421, 455 };
    private static final short[] kSpeechDataStds = { 555, 505, 567, 524, 585, 1231, 509, 828, 492, 1540, 1079, 850 };
    private static final int kMaxSpeechFrames = 6;
    private static final int kMinStd = 384;

    private static final short[][] kOverHangMax1 = { { 8, 4, 3 }, { 8, 4, 3 }, { 6, 3, 2 }, { 6, 3, 2 } };
    private static final short[][] kOverHangMax2 = { { 14, 7, 5 }, { 14, 7, 5 }, { 9, 5, 3 }, { 9, 5, 3 } };
    private static final short[][] kLocalThreshold = { { 24, 21, 24 }, { 37, 32, 37 }, { 82, 78, 82 }, { 94, 94, 94 } };
    private static final short[][] kGlobalThreshold = { { 57, 48, 57 }, { 100, 80, 100 }, { 285, 260, 285 }, { 1100, 1050, 1100 } };

    // ---- vad_filterbank.c ----
    private static final int kLogConst = 24660;
    private static final int kLogEnergyIntPart = 14336;
    private static final short[] kHpZeroCoefs = { 6631, -13262, 6631 };
    private static final short[] kHpPoleCoefs = { 16384, -7756, 5620 };
    private static final short[] kAllPassCoefsQ15 = { 20972, 5571 };
    private static final short[] kOffsetVector = { 368, 368, 272, 176, 176, 176 };

    // ---- vad_gmm.c ----
    private static final int kCompVar = 22005;
    private static final int kLog2Exp = 5909;

    // ---- vad_sp.c ----
    private static final short[] kAllPassCoefsQ13 = { 5243, 1392 };
    private static final int kSmoothingDown = 6553;
    private static final int kSmoothingUp = 32439;

    // ---- the state (VadInstT) ----
    private final int[] downsamplingFilterStates = new int[4];
    private final short[] noiseMeans = new short[kTableSize];
    private final short[] speechMeans = new short[kTableSize];
    private final short[] noiseStds = new short[kTableSize];
    private final short[] speechStds = new short[kTableSize];
    private int frameCounter;
    private short overHang;
    private short numOfSpeech;
    private final short[] indexVector = new short[16 * kNumChannels];
    private final short[] lowValueVector = new short[16 * kNumChannels];
    private final short[] meanValue = new short[kNumChannels];
    private final short[] upperState = new short[5];
    private final short[] lowerState = new short[5];
    private final short[] hpFilterState = new short[4];
    private final short[] overHangMax1 = new short[3];
    private final short[] overHangMax2 = new short[3];
    private final short[] individual = new short[3];
    private final short[] total = new short[3];

    /** the band energies of the last frame (10 log10, Q4): 80–250, 250–500, 500–1000, 1000–2000, 2000–3000, 3000–4000 Hz */
    public final short[] features = new short[kNumChannels];
    /** the total power of the last frame as the VAD sees it (above 10: processed) */
    public short totalPower;
    /** the decision of the last frame without the hangover of the VAD (a click is no speech for several frames after it) */
    public boolean lastRaw;

    /** [mode] 0 quality … 3 very aggressive (fewer frames of noise are called speech) */
    public WebRtcVad(int mode) {
        initCore();
        setMode(mode);
    }

    private void initCore() {
        frameCounter = 0;
        overHang = 0;
        numOfSpeech = 0;
        java.util.Arrays.fill(downsamplingFilterStates, 0);
        for (int i = 0; i < kTableSize; i++) {
            noiseMeans[i] = kNoiseDataMeans[i];
            speechMeans[i] = kSpeechDataMeans[i];
            noiseStds[i] = kNoiseDataStds[i];
            speechStds[i] = kSpeechDataStds[i];
        }
        for (int i = 0; i < 16 * kNumChannels; i++) {
            lowValueVector[i] = 10000;
            indexVector[i] = 0;
        }
        java.util.Arrays.fill(upperState, (short) 0);
        java.util.Arrays.fill(lowerState, (short) 0);
        java.util.Arrays.fill(hpFilterState, (short) 0);
        for (int i = 0; i < kNumChannels; i++) meanValue[i] = 1600;
    }

    public void setMode(int mode) {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("mode " + mode);
        System.arraycopy(kOverHangMax1[mode], 0, overHangMax1, 0, 3);
        System.arraycopy(kOverHangMax2[mode], 0, overHangMax2, 0, 3);
        System.arraycopy(kLocalThreshold[mode], 0, individual, 0, 3);
        System.arraycopy(kGlobalThreshold[mode], 0, total, 0, 3);
    }

    /** WebRtcVad_Process for 8000 or 16000 Hz and 10, 20 or 30 ms; true: speech (with the hangover of the VAD) */
    public boolean process(int sampleRate, short[] frame, int length) {
        if (!(sampleRate == 8000 || sampleRate == 16000)) throw new IllegalArgumentException("rate " + sampleRate);
        int ms = length * 1000 / sampleRate;
        if (length * 1000 % sampleRate != 0 || (ms != 10 && ms != 20 && ms != 30)) throw new IllegalArgumentException("length " + length);
        int vad;
        if (sampleRate == 16000) {
            short[] nb = new short[240];
            downsampling(frame, nb, downsamplingFilterStates, length);
            vad = calcVad8khz(nb, length / 2);
        } else {
            vad = calcVad8khz(frame, length);
        }
        return vad > 0;
    }

    private int calcVad8khz(short[] frame, int length) {
        totalPower = calculateFeatures(frame, length, features);
        return gmmProbability(features, totalPower, length);
    }

    // ---- signal processing helpers ----

    private static int countLeadingZeros32(int n) { return n == 0 ? 32 : Integer.numberOfLeadingZeros(n); }

    private static short normW32(int a) { return (short) (a == 0 ? 0 : countLeadingZeros32(a < 0 ? ~a : a) - 1); }

    private static short normU32(int a) { return (short) (a == 0 ? 0 : countLeadingZeros32(a)); }

    private static short getSizeInBits(int n) { return (short) (32 - countLeadingZeros32(n)); }

    private static int divW32W16(int num, short den) { return den != 0 ? num / den : 0x7FFFFFFF; }

    private static short getScalingSquare(short[] v, int off, int len, int times) {
        short nbits = getSizeInBits(times);
        short smax = -1;
        for (int i = 0; i < len; i++) {
            short s = v[off + i];
            short sabs = (short) (s > 0 ? s : -s);
            if (sabs > smax) smax = sabs;
        }
        short t = normW32(smax * smax);
        if (smax == 0) return 0;
        return (short) (t > nbits ? 0 : nbits - t);
    }

    /** returns the energy; scale[0] gets the scaling */
    private static int energy(short[] v, int off, int len, int[] scale) {
        int en = 0;
        int scaling = getScalingSquare(v, off, len, len);
        for (int i = 0; i < len; i++) en += (v[off + i] * v[off + i]) >> scaling;
        scale[0] = scaling;
        return en;
    }

    // ---- vad_sp.c ----

    private static void downsampling(short[] in, short[] out, int[] state, int inLength) {
        int tmp32_1 = state[0];
        int tmp32_2 = state[1];
        int half = inLength >> 1;
        int ip = 0;
        for (int n = 0; n < half; n++) {
            short tmp16_1 = (short) ((tmp32_1 >> 1) + ((kAllPassCoefsQ13[0] * in[ip]) >> 14));
            out[n] = tmp16_1;
            tmp32_1 = in[ip++] - ((kAllPassCoefsQ13[0] * tmp16_1) >> 12);
            short tmp16_2 = (short) ((tmp32_2 >> 1) + ((kAllPassCoefsQ13[1] * in[ip]) >> 14));
            out[n] = (short) (out[n] + tmp16_2);
            tmp32_2 = in[ip++] - ((kAllPassCoefsQ13[1] * tmp16_2) >> 12);
        }
        state[0] = tmp32_1;
        state[1] = tmp32_2;
    }

    private short findMinimum(short featureValue, int channel) {
        int position = -1;
        int offset = channel << 4;
        short currentMedian = 1600;
        int alpha = 0;
        for (int i = 0; i < 16; i++) {
            if (indexVector[offset + i] != 100) {
                indexVector[offset + i]++;
            } else {
                for (int j = i; j < 15; j++) {
                    lowValueVector[offset + j] = lowValueVector[offset + j + 1];
                    indexVector[offset + j] = indexVector[offset + j + 1];
                }
                indexVector[offset + 15] = 101;
                lowValueVector[offset + 15] = 10000;
            }
        }
        short[] sv = lowValueVector;
        int o = offset;
        if (featureValue < sv[o + 7]) {
            if (featureValue < sv[o + 3]) {
                if (featureValue < sv[o + 1]) position = featureValue < sv[o] ? 0 : 1;
                else position = featureValue < sv[o + 2] ? 2 : 3;
            } else if (featureValue < sv[o + 5]) {
                position = featureValue < sv[o + 4] ? 4 : 5;
            } else {
                position = featureValue < sv[o + 6] ? 6 : 7;
            }
        } else if (featureValue < sv[o + 15]) {
            if (featureValue < sv[o + 11]) {
                if (featureValue < sv[o + 9]) position = featureValue < sv[o + 8] ? 8 : 9;
                else position = featureValue < sv[o + 10] ? 10 : 11;
            } else if (featureValue < sv[o + 13]) {
                position = featureValue < sv[o + 12] ? 12 : 13;
            } else {
                position = featureValue < sv[o + 14] ? 14 : 15;
            }
        }
        if (position > -1) {
            for (int i = 15; i > position; i--) {
                sv[o + i] = sv[o + i - 1];
                indexVector[o + i] = indexVector[o + i - 1];
            }
            sv[o + position] = featureValue;
            indexVector[o + position] = 1;
        }
        if (frameCounter > 2) currentMedian = sv[o + 2];
        else if (frameCounter > 0) currentMedian = sv[o];
        if (frameCounter > 0) alpha = currentMedian < meanValue[channel] ? kSmoothingDown : kSmoothingUp;
        int tmp32 = (alpha + 1) * meanValue[channel];
        tmp32 += (32767 - alpha) * currentMedian;
        tmp32 += 16384;
        meanValue[channel] = (short) (tmp32 >> 15);
        return meanValue[channel];
    }

    // ---- vad_filterbank.c ----

    private static void highPassFilter(short[] in, int length, short[] state, short[] out) {
        for (int i = 0; i < length; i++) {
            int tmp32 = kHpZeroCoefs[0] * in[i];
            tmp32 += kHpZeroCoefs[1] * state[0];
            tmp32 += kHpZeroCoefs[2] * state[1];
            state[1] = state[0];
            state[0] = in[i];
            tmp32 -= kHpPoleCoefs[1] * state[2];
            tmp32 -= kHpPoleCoefs[2] * state[3];
            state[3] = state[2];
            state[2] = (short) (tmp32 >> 14);
            out[i] = state[2];
        }
    }

    /** data_in read with a stride of 2 from [inOff]; state is one element of [states] */
    private static void allPassFilter(short[] in, int inOff, int length, int coef, short[] states, int stateIndex, short[] out) {
        int state32 = states[stateIndex] * (1 << 16);
        int ip = inOff;
        for (int i = 0; i < length; i++) {
            int tmp32 = state32 + coef * in[ip];
            short tmp16 = (short) (tmp32 >> 16);
            out[i] = tmp16;
            state32 = (in[ip] * (1 << 14)) - coef * tmp16;
            state32 *= 2;
            ip += 2;
        }
        states[stateIndex] = (short) (state32 >> 16);
    }

    private void splitFilter(short[] in, int length, int band, short[] hpOut, short[] lpOut) {
        int half = length >> 1;
        allPassFilter(in, 0, half, kAllPassCoefsQ15[0], upperState, band, hpOut);
        allPassFilter(in, 1, half, kAllPassCoefsQ15[1], lowerState, band, lpOut);
        for (int i = 0; i < half; i++) {
            short tmp = hpOut[i];
            hpOut[i] = (short) (hpOut[i] - lpOut[i]);
            lpOut[i] = (short) (lpOut[i] + tmp);
        }
    }

    private final int[] scaleBox = new int[1];

    /** totalEnergy[0] in and out; returns the log energy */
    private short logOfEnergy(short[] in, int length, short offset, short[] totalEnergy) {
        int energy = energy(in, 0, length, scaleBox); // used as uint32
        int totRshifts = scaleBox[0];
        short logEnergy;
        if (energy != 0) {
            int normalizingRshifts = 17 - normU32(energy);
            short log2Energy = (short) kLogEnergyIntPart;
            totRshifts += normalizingRshifts;
            if (normalizingRshifts < 0) energy <<= -normalizingRshifts;
            else energy >>>= normalizingRshifts;
            log2Energy = (short) (log2Energy + (short) ((energy & 0x00003FFF) >>> 4));
            logEnergy = (short) (((kLogConst * log2Energy) >> 19) + ((totRshifts * kLogConst) >> 9));
            if (logEnergy < 0) logEnergy = 0;
        } else {
            return offset;
        }
        logEnergy = (short) (logEnergy + offset);
        if (totalEnergy[0] <= kMinEnergy) {
            if (totRshifts >= 0) totalEnergy[0] = (short) (totalEnergy[0] + kMinEnergy + 1);
            else totalEnergy[0] = (short) (totalEnergy[0] + (short) (energy >>> -totRshifts));
        }
        return logEnergy;
    }

    private final short[] hp120 = new short[120], lp120 = new short[120], hp60 = new short[60], lp60 = new short[60];
    private final short[] totalBox = new short[1];

    private short calculateFeatures(short[] in, int length, short[] f) {
        totalBox[0] = 0;
        int half = length >> 1;
        int len = half;
        splitFilter(in, length, 0, hp120, lp120);
        splitFilter(hp120, len, 1, hp60, lp60);
        len >>= 1;
        f[5] = logOfEnergy(hp60, len, kOffsetVector[5], totalBox);
        f[4] = logOfEnergy(lp60, len, kOffsetVector[4], totalBox);
        len = half;
        splitFilter(lp120, len, 2, hp60, lp60);
        len >>= 1;
        f[3] = logOfEnergy(hp60, len, kOffsetVector[3], totalBox);
        splitFilter(lp60, len, 3, hp120, lp120);
        len >>= 1;
        f[2] = logOfEnergy(hp120, len, kOffsetVector[2], totalBox);
        splitFilter(lp120, len, 4, hp60, lp60);
        len >>= 1;
        f[1] = logOfEnergy(hp60, len, kOffsetVector[1], totalBox);
        highPassFilter(lp60, len, hpFilterState, hp120);
        f[0] = logOfEnergy(hp120, len, kOffsetVector[0], totalBox);
        return totalBox[0];
    }

    // ---- vad_gmm.c ----

    private static int gaussianProbability(short input, short mean, short std, short[] delta, int di) {
        short tmp16, invStd, invStd2, expValue = 0;
        int tmp32 = 131072 + (std >> 1);
        invStd = (short) divW32W16(tmp32, std);
        tmp16 = (short) (invStd >> 2);
        invStd2 = (short) ((tmp16 * tmp16) >> 2);
        tmp16 = (short) (input << 3);
        tmp16 = (short) (tmp16 - mean);
        delta[di] = (short) ((invStd2 * tmp16) >> 10);
        tmp32 = (delta[di] * tmp16) >> 9;
        if (tmp32 < kCompVar) {
            tmp16 = (short) ((kLog2Exp * tmp32) >> 12);
            tmp16 = (short) -tmp16;
            expValue = (short) (0x0400 | (tmp16 & 0x03FF));
            tmp16 = (short) (tmp16 ^ 0xFFFF);
            tmp16 = (short) (tmp16 >> 10);
            tmp16 = (short) (tmp16 + 1);
            expValue = (short) (expValue >> tmp16);
        }
        return invStd * expValue;
    }

    // ---- vad_core.c, GmmProbability ----

    private static int weightedAverage(short[] data, int off, short offset, short[] weights, int woff) {
        int weighted = 0;
        for (int k = 0; k < kNumGaussians; k++) {
            data[off + k * kNumChannels] = (short) (data[off + k * kNumChannels] + offset);
            weighted += data[off + k * kNumChannels] * weights[woff + k * kNumChannels];
        }
        return weighted;
    }

    private final short[] deltaN = new short[kTableSize], deltaS = new short[kTableSize];
    private final short[] ngprvec = new short[kTableSize], sgprvec = new short[kTableSize];
    private final int[] noiseProbability = new int[kNumGaussians], speechProbability = new int[kNumGaussians];

    private int gmmProbability(short[] features, short totalPower, int frameLength) {
        short vadflag = 0;
        int sumLogLikelihoodRatios = 0;
        java.util.Arrays.fill(ngprvec, (short) 0);
        java.util.Arrays.fill(sgprvec, (short) 0);
        int idx = frameLength == 80 ? 0 : frameLength == 160 ? 1 : 2;
        short overhead1 = overHangMax1[idx], overhead2 = overHangMax2[idx];
        short individualTest = individual[idx], totalTest = total[idx];

        if (totalPower > kMinEnergy) {
            for (int channel = 0; channel < kNumChannels; channel++) {
                int h0Test = 0, h1Test = 0;
                for (int k = 0; k < kNumGaussians; k++) {
                    int g = channel + k * kNumChannels;
                    int tmp1 = gaussianProbability(features[channel], noiseMeans[g], noiseStds[g], deltaN, g);
                    noiseProbability[k] = kNoiseDataWeights[g] * tmp1;
                    h0Test += noiseProbability[k];
                    tmp1 = gaussianProbability(features[channel], speechMeans[g], speechStds[g], deltaS, g);
                    speechProbability[k] = kSpeechDataWeights[g] * tmp1;
                    h1Test += speechProbability[k];
                }
                short shiftsH0 = normW32(h0Test);
                short shiftsH1 = normW32(h1Test);
                if (h0Test == 0) shiftsH0 = 31;
                if (h1Test == 0) shiftsH1 = 31;
                short llr = (short) (shiftsH0 - shiftsH1);
                sumLogLikelihoodRatios += llr * kSpectrumWeight[channel];
                if ((llr * 4) > individualTest) vadflag = 1;
                short h0 = (short) (h0Test >> 12);
                if (h0 > 0) {
                    int tmp1 = (noiseProbability[0] & 0xFFFFF000) << 2;
                    ngprvec[channel] = (short) divW32W16(tmp1, h0);
                    ngprvec[channel + kNumChannels] = (short) (16384 - ngprvec[channel]);
                } else {
                    ngprvec[channel] = 16384;
                }
                short h1 = (short) (h1Test >> 12);
                if (h1 > 0) {
                    int tmp1 = (speechProbability[0] & 0xFFFFF000) << 2;
                    sgprvec[channel] = (short) divW32W16(tmp1, h1);
                    sgprvec[channel + kNumChannels] = (short) (16384 - sgprvec[channel]);
                }
            }
            if (sumLogLikelihoodRatios >= totalTest) vadflag |= 1;

            short maxspe = 12800;
            for (int channel = 0; channel < kNumChannels; channel++) {
                short featureMinimum = findMinimum(features[channel], channel);
                int noiseGlobalMean = weightedAverage(noiseMeans, channel, (short) 0, kNoiseDataWeights, channel);
                short tmp1_s16 = (short) (noiseGlobalMean >> 6);
                for (int k = 0; k < kNumGaussians; k++) {
                    int g = channel + k * kNumChannels;
                    short nmk = noiseMeans[g], smk = speechMeans[g], nsk = noiseStds[g], ssk = speechStds[g];
                    short nmk2 = nmk;
                    if (vadflag == 0) {
                        short delt = (short) ((ngprvec[g] * deltaN[g]) >> 11);
                        nmk2 = (short) (nmk + (short) ((delt * kNoiseUpdateConst) >> 22));
                    }
                    short ndelt = (short) ((featureMinimum << 4) - tmp1_s16);
                    short nmk3 = (short) (nmk2 + (short) ((ndelt * kBackEta) >> 9));
                    short tmp_s16 = (short) ((k + 5) << 7);
                    if (nmk3 < tmp_s16) nmk3 = tmp_s16;
                    tmp_s16 = (short) ((72 + k - channel) << 7);
                    if (nmk3 > tmp_s16) nmk3 = tmp_s16;
                    noiseMeans[g] = nmk3;

                    if (vadflag != 0) {
                        short delt = (short) ((sgprvec[g] * deltaS[g]) >> 11);
                        tmp_s16 = (short) ((delt * kSpeechUpdateConst) >> 21);
                        short smk2 = (short) (smk + ((tmp_s16 + 1) >> 1));
                        short maxmu = (short) (maxspe + 640);
                        if (smk2 < kMinimumMean[k]) smk2 = kMinimumMean[k];
                        if (smk2 > maxmu) smk2 = maxmu;
                        speechMeans[g] = smk2;
                        tmp_s16 = (short) ((smk + 4) >> 3);
                        tmp_s16 = (short) (features[channel] - tmp_s16);
                        int tmp1_s32 = (deltaS[g] * tmp_s16) >> 3;
                        int tmp2_s32 = tmp1_s32 - 4096;
                        tmp_s16 = (short) (sgprvec[g] >> 2);
                        tmp1_s32 = tmp_s16 * tmp2_s32;
                        tmp2_s32 = tmp1_s32 >> 4;
                        if (tmp2_s32 > 0) {
                            tmp_s16 = (short) divW32W16(tmp2_s32, (short) (ssk * 10));
                        } else {
                            tmp_s16 = (short) divW32W16(-tmp2_s32, (short) (ssk * 10));
                            tmp_s16 = (short) -tmp_s16;
                        }
                        tmp_s16 = (short) (tmp_s16 + 128);
                        ssk = (short) (ssk + (tmp_s16 >> 8));
                        if (ssk < kMinStd) ssk = kMinStd;
                        speechStds[g] = ssk;
                    } else {
                        tmp_s16 = (short) (features[channel] - (nmk >> 3));
                        int tmp1_s32 = (deltaN[g] * tmp_s16) >> 3;
                        tmp1_s32 -= 4096;
                        tmp_s16 = (short) ((ngprvec[g] + 2) >> 2);
                        int tmp2_s32 = tmp_s16 * tmp1_s32;
                        tmp1_s32 = tmp2_s32 >> 14;
                        if (tmp1_s32 > 0) {
                            tmp_s16 = (short) divW32W16(tmp1_s32, nsk);
                        } else {
                            tmp_s16 = (short) divW32W16(-tmp1_s32, nsk);
                            tmp_s16 = (short) -tmp_s16;
                        }
                        tmp_s16 = (short) (tmp_s16 + 32);
                        nsk = (short) (nsk + (tmp_s16 >> 6));
                        if (nsk < kMinStd) nsk = kMinStd;
                        noiseStds[g] = nsk;
                    }
                }
                noiseGlobalMean = weightedAverage(noiseMeans, channel, (short) 0, kNoiseDataWeights, channel);
                int speechGlobalMean = weightedAverage(speechMeans, channel, (short) 0, kSpeechDataWeights, channel);
                short diff = (short) ((short) (speechGlobalMean >> 9) - (short) (noiseGlobalMean >> 9));
                if (diff < kMinimumDifference[channel]) {
                    short tmp_s16 = (short) (kMinimumDifference[channel] - diff);
                    short t1 = (short) ((13 * tmp_s16) >> 2);
                    short t2 = (short) ((3 * tmp_s16) >> 2);
                    speechGlobalMean = weightedAverage(speechMeans, channel, t1, kSpeechDataWeights, channel);
                    noiseGlobalMean = weightedAverage(noiseMeans, channel, (short) -t2, kNoiseDataWeights, channel);
                }
                maxspe = kMaximumSpeech[channel];
                short tmp2_s16 = (short) (speechGlobalMean >> 7);
                if (tmp2_s16 > maxspe) {
                    tmp2_s16 = (short) (tmp2_s16 - maxspe);
                    for (int k = 0; k < kNumGaussians; k++) {
                        int g = channel + k * kNumChannels;
                        speechMeans[g] = (short) (speechMeans[g] - tmp2_s16);
                    }
                }
                tmp2_s16 = (short) (noiseGlobalMean >> 7);
                if (tmp2_s16 > kMaximumNoise[channel]) {
                    tmp2_s16 = (short) (tmp2_s16 - kMaximumNoise[channel]);
                    for (int k = 0; k < kNumGaussians; k++) {
                        int g = channel + k * kNumChannels;
                        noiseMeans[g] = (short) (noiseMeans[g] - tmp2_s16);
                    }
                }
            }
            frameCounter++;
        }

        lastRaw = vadflag == 1;
        if (vadflag == 0) {
            if (overHang > 0) {
                vadflag = (short) (2 + overHang);
                overHang--;
            }
            numOfSpeech = 0;
        } else {
            numOfSpeech++;
            if (numOfSpeech > kMaxSpeechFrames) {
                numOfSpeech = kMaxSpeechFrames;
                overHang = overhead2;
            } else {
                overHang = overhead1;
            }
        }
        return vadflag;
    }
}
