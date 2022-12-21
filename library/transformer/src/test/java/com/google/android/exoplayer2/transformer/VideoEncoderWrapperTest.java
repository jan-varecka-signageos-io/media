/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.transformer;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.ListenerSet;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.SurfaceInfo;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowMediaCodecList;

/** Unit tests for {@link VideoTranscodingSamplePipeline.EncoderWrapper}. */
@RunWith(AndroidJUnit4.class)
public final class VideoEncoderWrapperTest {
  private final TransformationRequest emptyTransformationRequest =
      new TransformationRequest.Builder().build();
  private final FakeVideoEncoderFactory fakeEncoderFactory = new FakeVideoEncoderFactory();
  private final FallbackListener fallbackListener =
      new FallbackListener(
          MediaItem.fromUri(Uri.EMPTY),
          new ListenerSet<>(Looper.myLooper(), Clock.DEFAULT, (listener, flags) -> {}),
          Clock.DEFAULT.createHandler(Looper.myLooper(), /* callback= */ null),
          emptyTransformationRequest);
  private final VideoTranscodingSamplePipeline.EncoderWrapper encoderWrapper =
      new VideoTranscodingSamplePipeline.EncoderWrapper(
          fakeEncoderFactory,
          /* inputFormat= */ new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
          /* muxerSupportedMimeTypes= */ ImmutableList.of(MimeTypes.VIDEO_H264),
          emptyTransformationRequest,
          fallbackListener);

  @Before
  public void setUp() {
    fallbackListener.setTrackCount(1);
    createShadowH264Encoder();
  }

  @After
  public void tearDown() {
    ShadowMediaCodec.clearCodecs();
    ShadowMediaCodecList.reset();
    EncoderUtil.clearCachedEncoders();
  }

  @Test
  public void getSurfaceInfo_landscape_leavesOrientationUnchanged() throws Exception {
    int inputWidth = 200;
    int inputHeight = 150;

    SurfaceInfo surfaceInfo = encoderWrapper.getSurfaceInfo(inputWidth, inputHeight);

    assertThat(surfaceInfo.orientationDegrees).isEqualTo(0);
    assertThat(surfaceInfo.width).isEqualTo(inputWidth);
    assertThat(surfaceInfo.height).isEqualTo(inputHeight);
  }

  @Test
  public void getSurfaceInfo_square_leavesOrientationUnchanged() throws Exception {
    int inputWidth = 150;
    int inputHeight = 150;

    SurfaceInfo surfaceInfo = encoderWrapper.getSurfaceInfo(inputWidth, inputHeight);

    assertThat(surfaceInfo.orientationDegrees).isEqualTo(0);
    assertThat(surfaceInfo.width).isEqualTo(inputWidth);
    assertThat(surfaceInfo.height).isEqualTo(inputHeight);
  }

  @Test
  public void getSurfaceInfo_portrait_flipsOrientation() throws Exception {
    int inputWidth = 150;
    int inputHeight = 200;

    SurfaceInfo surfaceInfo = encoderWrapper.getSurfaceInfo(inputWidth, inputHeight);

    assertThat(surfaceInfo.orientationDegrees).isEqualTo(90);
    assertThat(surfaceInfo.width).isEqualTo(inputHeight);
    assertThat(surfaceInfo.height).isEqualTo(inputWidth);
  }

  @Test
  public void getSurfaceInfo_withEncoderFallback_usesFallbackResolution()
      throws TransformationException {
    int inputWidth = 200;
    int inputHeight = 150;
    int fallbackWidth = 100;
    int fallbackHeight = 75;
    fakeEncoderFactory.setFallbackResolution(fallbackWidth, fallbackHeight);

    SurfaceInfo surfaceInfo = encoderWrapper.getSurfaceInfo(inputWidth, inputHeight);

    assertThat(surfaceInfo.orientationDegrees).isEqualTo(0);
    assertThat(surfaceInfo.width).isEqualTo(fallbackWidth);
    assertThat(surfaceInfo.height).isEqualTo(fallbackHeight);
  }

  private static void createShadowH264Encoder() {
    MediaFormat avcFormat = new MediaFormat();
    avcFormat.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
    MediaCodecInfo.CodecProfileLevel profileLevel = new MediaCodecInfo.CodecProfileLevel();
    profileLevel.profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;
    // Using Level4 gives us 8192 16x16 blocks. If using width 1920 uses 120 blocks, 8192 / 120 = 68
    // blocks will be left for encoding height 1088.
    profileLevel.level = MediaCodecInfo.CodecProfileLevel.AVCLevel4;

    createShadowVideoEncoder(avcFormat, profileLevel, "test.transformer.avc.encoder");
  }

  private static void createShadowVideoEncoder(
      MediaFormat supportedFormat,
      MediaCodecInfo.CodecProfileLevel supportedProfileLevel,
      String name) {
    // ShadowMediaCodecList is static. The added encoders will be visible for every test.
    ShadowMediaCodecList.addCodec(
        MediaCodecInfoBuilder.newBuilder()
            .setName(name)
            .setIsEncoder(true)
            .setCapabilities(
                MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(supportedFormat)
                    .setIsEncoder(true)
                    .setColorFormats(
                        new int[] {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible})
                    .setProfileLevels(
                        new MediaCodecInfo.CodecProfileLevel[] {supportedProfileLevel})
                    .build())
            .build());
  }

  private static class FakeVideoEncoderFactory implements Codec.EncoderFactory {

    private int fallbackWidth;
    private int fallbackHeight;

    public FakeVideoEncoderFactory() {
      fallbackWidth = C.LENGTH_UNSET;
      fallbackHeight = C.LENGTH_UNSET;
    }

    public void setFallbackResolution(int fallbackWidth, int fallbackHeight) {
      this.fallbackWidth = fallbackWidth;
      this.fallbackHeight = fallbackHeight;
    }

    @Override
    public Codec createForAudioEncoding(Format format) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Codec createForVideoEncoding(Format format) {
      Codec mockEncoder = mock(Codec.class);
      if (fallbackWidth != C.LENGTH_UNSET) {
        format = format.buildUpon().setWidth(fallbackWidth).build();
      }
      if (fallbackHeight != C.LENGTH_UNSET) {
        format = format.buildUpon().setHeight(fallbackHeight).build();
      }
      when(mockEncoder.getConfigurationFormat()).thenReturn(format);
      return mockEncoder;
    }
  }
}
