package com.example.xr.gl

import android.opengl.GLES30
import android.util.Log

object ShaderPrograms {

    private const val TAG = "ShaderPrograms"

    fun compileProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES30.glDeleteShader(vs)
            if (fs != 0) GLES30.glDeleteShader(fs)
            return 0
        }
        val prog = GLES30.glCreateProgram()
        if (prog == 0) {
            Log.e(TAG, "glCreateProgram returned 0 (err=0x${Integer.toHexString(GLES30.glGetError())})")
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return 0
        }
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (linkStatus[0] == 0) {
            val info = GLES30.glGetProgramInfoLog(prog)
            Log.e(TAG, "Program link failed: $info")
            GLES30.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            Log.e(TAG, "glCreateShader($type) returned 0")
            return 0
        }
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val info = GLES30.glGetShaderInfoLog(shader)
            val label = if (type == GLES30.GL_VERTEX_SHADER) "VERTEX" else "FRAGMENT"
            Log.e(TAG, "$label shader compile failed: $info")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    const val DISTORTION_VERT = """#version 300 es
        layout(location = 0) in vec2 aPos;
        layout(location = 1) in vec2 aRedUv;
        layout(location = 2) in vec2 aGreenUv;
        layout(location = 3) in vec2 aBlueUv;
        layout(location = 4) in float aVignette;
        out vec2 vRedUv;
        out vec2 vGreenUv;
        out vec2 vBlueUv;
        out float vVignette;
        void main() {
            vRedUv = aRedUv;
            vGreenUv = aGreenUv;
            vBlueUv = aBlueUv;
            vVignette = aVignette;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    const val DISTORTION_FRAG = """#version 300 es
        precision mediump float;
        uniform sampler2D uEyeTexture;
        uniform mat3 uTimewarpMat;
        in vec2 vRedUv;
        in vec2 vGreenUv;
        in vec2 vBlueUv;
        in float vVignette;
        out vec4 fragColor;
        vec2 warpUv(vec2 uv) {
            vec3 p = uTimewarpMat * vec3(uv - 0.5, 1.0);
            return (p.xy / max(p.z, 0.35)) + 0.5;
        }
        void main() {
            vec2 gUv = warpUv(vGreenUv);
            if (gUv.x < 0.0 || gUv.x > 1.0 || gUv.y < 0.0 || gUv.y > 1.0) {
                fragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }
            vec2 rUv = warpUv(vRedUv);
            vec2 bUv = warpUv(vBlueUv);
            float r = texture(uEyeTexture, clamp(rUv, 0.0, 1.0)).r;
            float g = texture(uEyeTexture, clamp(gUv, 0.0, 1.0)).g;
            float b = texture(uEyeTexture, clamp(bUv, 0.0, 1.0)).b;
            fragColor = vec4(vec3(r, g, b) * vVignette, 1.0);
        }
    """

    const val OES_WINDOW_VERT = """#version 300 es
        uniform mat4 uMvp;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec2 aUv;
        out vec2 vUv;
        void main() {
            vUv = aUv;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """

    const val OES_WINDOW_FRAG = """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        uniform samplerExternalOES uOesTex;
        uniform vec4 uBorderColor;
        uniform float uOpacity;
        uniform float uAspect;
        uniform int uCinemaStereoMode;
        uniform float uEyeIndex;
        uniform vec2 uContentFitScale;
        in vec2 vUv;
        out vec4 fragColor;
        void main() {
            vec2 p = (vUv - 0.5) * vec2(max(uAspect, 0.5), 1.0);
            vec2 b = vec2(0.5 * max(uAspect, 0.5), 0.5) - vec2(0.032);
            vec2 q = abs(p) - b;
            float sdf = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - 0.032;
            float alphaMask = 1.0 - smoothstep(-0.004, 0.004, sdf);
            if (alphaMask <= 0.001) discard;
            float rim = smoothstep(-0.014, -0.002, sdf);

            vec2 fitScale = max(uContentFitScale, vec2(0.25));
            vec2 fittedUv = (vec2(vUv.x, 1.0 - vUv.y) - 0.5) / fitScale + 0.5;
            if (fittedUv.x < 0.0 || fittedUv.x > 1.0 || fittedUv.y < 0.0 || fittedUv.y > 1.0) {
                vec4 rimOnly = vec4(uBorderColor.rgb * uBorderColor.a, uBorderColor.a);
                vec4 letterbox = vec4(0.02, 0.03, 0.06, uOpacity);
                fragColor = mix(letterbox, rimOnly, rim * 0.85) * alphaMask;
                return;
            }
            vec2 texUv = fittedUv;
            if (uCinemaStereoMode == 1) {
                texUv.x = (uEyeIndex * 0.5) + texUv.x * 0.5;
            } else if (uCinemaStereoMode == 2) {
                vec2 c = texUv - 0.5;
                texUv = vec2(0.5 + c.x * 0.88, 0.5 + c.y * 0.92);
            } else if (uCinemaStereoMode == 3) {
                // 360 spherical latitude-longitude remap inside stereo theater dome quad
                vec2 c = (texUv - 0.5) * 2.0;
                float lon = c.x * 1.570796;
                float lat = c.y * 1.15;
                texUv = vec2(0.5 + lon / 6.283185 + (uEyeIndex * 0.004), 0.5 + lat / 3.141592);
            }
            vec4 tex = texture(uOesTex, clamp(texUv, 0.001, 0.999));
            float a = tex.a * uOpacity;
            vec4 bodyPremul = vec4(tex.rgb * a, a);
            vec4 rimPremul = vec4(uBorderColor.rgb * uBorderColor.a, uBorderColor.a);
            fragColor = mix(bodyPremul, rimPremul, rim * 0.85) * alphaMask;
        }
    """

    const val TEX_2D_WINDOW_FRAG = """#version 300 es
        precision mediump float;
        uniform sampler2D uTex2d;
        uniform vec4 uBorderColor;
        uniform float uOpacity;
        uniform float uAspect;
        in vec2 vUv;
        out vec4 fragColor;
        void main() {
            vec2 p = (vUv - 0.5) * vec2(max(uAspect, 0.5), 1.0);
            vec2 b = vec2(0.5 * max(uAspect, 0.5), 0.5) - vec2(0.036);
            vec2 q = abs(p) - b;
            float sdf = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - 0.036;
            float alphaMask = 1.0 - smoothstep(-0.004, 0.004, sdf);
            if (alphaMask <= 0.001) discard;
            float rim = smoothstep(-0.014, -0.002, sdf);

            vec2 texUv = vec2(vUv.x, 1.0 - vUv.y);
            // Crisp UI sample blended with 4-tap frosted outer border softening (premuliplied RGBA)
            vec4 sharp = texture(uTex2d, texUv);
            vec2 eps = vec2(0.0016, 0.0022);
            vec4 frosted = (
                sharp * 2.0 +
                texture(uTex2d, clamp(texUv + vec2(eps.x, 0.0), 0.0, 1.0)) +
                texture(uTex2d, clamp(texUv - vec2(eps.x, 0.0), 0.0, 1.0)) +
                texture(uTex2d, clamp(texUv + vec2(0.0, eps.y), 0.0, 1.0)) +
                texture(uTex2d, clamp(texUv - vec2(0.0, eps.y), 0.0, 1.0))
            ) / 6.0;
            float glassRimFactor = smoothstep(-0.045, 0.0, sdf);
            vec4 tex = mix(sharp, frosted, glassRimFactor * 0.45) * uOpacity;
            vec4 rimPremul = vec4(uBorderColor.rgb * uBorderColor.a, uBorderColor.a);
            fragColor = mix(tex, rimPremul, rim * 0.85) * alphaMask;
        }
    """

    const val PASSTHROUGH_VERT = """#version 300 es
        uniform mat4 uMvp;
        uniform mat4 uCamTexMatrix;
        uniform int uRotationSteps;
        uniform float uMirrorX;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec2 aUv;
        out vec2 vUv;
        out vec2 vScreenUv;
        void main() {
            vScreenUv = aUv;
            vec2 c = aUv - 0.5;
            if (uMirrorX > 0.5) {
                c.x = -c.x;
            }
            if (uRotationSteps == 1) {
                c = vec2(-c.y, c.x);
            } else if (uRotationSteps == 2) {
                c = vec2(-c.x, -c.y);
            } else if (uRotationSteps == 3) {
                c = vec2(c.y, -c.x);
            }
            vec2 rotatedUv = c + 0.5;
            vUv = (uCamTexMatrix * vec4(rotatedUv, 0.0, 1.0)).xy;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """

    const val PASSTHROUGH_OES_FRAG = """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        uniform samplerExternalOES uCamTex;
        uniform float uBrightness;
        uniform float uUndistort;
        uniform float uImmersionAlpha;
        in vec2 vUv;
        in vec2 vScreenUv;
        out vec4 fragColor;
        void main() {
            if (uImmersionAlpha <= 0.01) discard;
            vec2 uv = vUv;
            if (uUndistort > 0.5) {
                vec2 d = (uv - 0.5) * 2.0;
                float r2 = dot(d, d);
                uv = 0.5 + d * (0.5 * (1.0 + 0.08 * r2));
            }
            vec3 col = texture(uCamTex, clamp(uv, 0.0, 1.0)).rgb * uBrightness;
            float distFromCenter = length(vScreenUv - 0.5) * 1.414;
            float feather = smoothstep(1.15, 0.35, distFromCenter);
            float outAlpha = clamp(uImmersionAlpha * mix(feather, 1.0, uImmersionAlpha), 0.0, 1.0);
            fragColor = vec4(col, outAlpha);
        }
    """

    const val COLOR_3D_VERT = """#version 300 es
        uniform mat4 uMvp;
        uniform float uPointSize;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec4 aColor;
        out vec4 vColor;
        void main() {
            vColor = aColor;
            gl_PointSize = uPointSize;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """

    const val COLOR_3D_FRAG = """#version 300 es
        precision mediump float;
        uniform float uBrightness;
        in vec4 vColor;
        out vec4 fragColor;
        void main() {
            fragColor = vec4(vColor.rgb * uBrightness, vColor.a);
        }
    """

    const val LIT_MESH_VERT = """#version 300 es
        uniform mat4 uMvp;
        uniform mat4 uModel;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        out vec3 vNormal;
        void main() {
            vNormal = mat3(uModel) * aNormal;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """

    const val LIT_MESH_FRAG = """#version 300 es
        precision mediump float;
        uniform vec3 uTint;
        in vec3 vNormal;
        out vec4 fragColor;
        void main() {
            vec3 n = normalize(vNormal);
            vec3 lightDir = normalize(vec3(0.4, 0.8, 0.6));
            float diff = max(dot(n, lightDir), 0.25);
            vec3 col = uTint * diff + vec3(0.08, 0.14, 0.25);
            fragColor = vec4(col, 1.0);
        }
    """
}
