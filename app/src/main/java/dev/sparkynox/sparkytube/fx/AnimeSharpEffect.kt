package dev.sparkynox.sparkytube.fx

import android.content.Context
import android.opengl.GLES20
import android.util.Size
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

// one GPU pass for anime: finds line edges, pulls the pixels next to them toward the edge (Anime4K's
// "push" idea, makes upscaled lines thin and crisp instead of blurry), then a mild sharpen that is
// clamped to the local range so it can't ring. runs after the upscale step
@UnstableApi
class AnimeSharpEffect(private val push: Float, private val sharp: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("Anime upscaler doesn't support HDR video")
        return AnimeSharpProgram(push, sharp)
    }
}

@UnstableApi
private class AnimeSharpProgram(push: Float, sharp: Float) : BaseGlShaderProgram(false, 1) {

    private val program: GlProgram = try {
        GlProgram(VERTEX, FRAGMENT)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    private var texel = floatArrayOf(1f, 1f)

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        program.setFloatUniform("uPush", push)
        program.setFloatUniform("uSharp", sharp)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texel = floatArrayOf(1f / inputWidth, 1f / inputHeight)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uTexel", texel)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""
        const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uPush;
uniform float uSharp;
varying vec2 vTexSamplingCoord;

vec3 tex(vec2 uv) { return texture2D(uTexSampler, uv).rgb; }
float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 d = uTexel;
  vec3 c  = tex(uv);
  vec3 n  = tex(uv + vec2(0.0, -d.y));
  vec3 s  = tex(uv + vec2(0.0,  d.y));
  vec3 w  = tex(uv + vec2(-d.x, 0.0));
  vec3 e  = tex(uv + vec2( d.x, 0.0));
  vec3 nw = tex(uv + vec2(-d.x, -d.y));
  vec3 ne = tex(uv + vec2( d.x, -d.y));
  vec3 sw = tex(uv + vec2(-d.x,  d.y));
  vec3 se = tex(uv + vec2( d.x,  d.y));

  float lc = lum(c);
  float ln = lum(n), ls = lum(s), lw = lum(w), le = lum(e);
  float lnw = lum(nw), lne = lum(ne), lsw = lum(sw), lse = lum(se);

  // sobel gradient: which way the edge faces and how strong it is
  float gx = (lne + 2.0 * le + lse) - (lnw + 2.0 * lw + lsw);
  float gy = (lsw + 2.0 * ls + lse) - (lnw + 2.0 * ln + lne);
  float mag = length(vec2(gx, gy));
  float edge = smoothstep(0.06, 0.4, mag);
  vec2 dir = mag > 0.0001 ? vec2(gx, gy) / mag : vec2(0.0);

  // look one pixel to each side of the edge and move toward the closer side
  vec3 a = tex(uv + dir * d);
  vec3 b = tex(uv - dir * d);
  vec3 side = abs(lc - lum(a)) < abs(lc - lum(b)) ? a : b;
  vec3 pushed = mix(c, side, edge * uPush * 0.6);

  // mild sharpen, weaker on strong edges, kept inside the neighbourhood range
  vec3 blur = (n + s + w + e) * 0.25;
  float amt = uSharp * (1.0 - 0.6 * edge);
  vec3 sharp = pushed + (pushed - blur) * amt;
  vec3 lo = min(min(min(n, s), min(w, e)), min(min(nw, ne), min(sw, min(se, c))));
  vec3 hi = max(max(max(n, s), max(w, e)), max(max(nw, ne), max(sw, max(se, c))));
  gl_FragColor = vec4(clamp(sharp, lo, hi), 1.0);
}
"""
    }
}
