package com.jfrmedia.switcher.studio.canvas

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Utilidades GL: shaders del compositor, texturas I420 y de imagen.
 * Todo se ejecuta en el hilo de dibujo con el contexto EGL activo.
 */
object Gl {
    fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader: $log\n$src")
        }
        return s
    }

    fun link(vsSrc: String, fsSrc: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vsSrc)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            throw RuntimeException("Programa: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    val quad: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            position(0)
        }
}

private const val VERT_SHADER = """
uniform mat4 uMvp;
attribute vec2 aQuad;
varying vec2 vQ;
void main() {
    vQ = aQuad;
    gl_Position = uMvp * vec4(aQuad, 0.0, 1.0);
}
"""

private const val FRAG_YUV = """
precision mediump float;
varying vec2 vQ;
uniform sampler2D uY;
uniform sampler2D uU;
uniform sampler2D uV;
uniform vec2 uUvOff;
uniform vec2 uUvScale;
uniform int uRot;
uniform float uMirror;
uniform float uOpacity;
uniform float uRadius;
uniform vec2 uHalfPx;
uniform vec2 uTexel;
uniform float uLuz;
uniform float uContraste;
uniform float uWb;

uniform float uColor;
uniform float uVineta;
uniform float uPoster;

vec2 rotUv(vec2 q) {
    if (uRot == 1) return vec2(q.y, 1.0 - q.x);
    if (uRot == 2) return vec2(1.0 - q.x, 1.0 - q.y);
    if (uRot == 3) return vec2(1.0 - q.y, q.x);
    return q;
}

float sdRound(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + vec2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - r;
}

void main() {
    vec2 uv = uUvOff + rotUv(vQ) * uUvScale;
    uv = clamp(uv, uTexel, vec2(1.0) - uTexel);
    if (uMirror > 0.5) uv.x = 1.0 - uv.x;
    // Sin voltear Y: al subir el plano I420, la fila 0 del cuadro queda en v=0 (arriba).
    float yy = (texture2D(uY, uv).r - 0.0625) * 1.164;
    float u = texture2D(uU, uv).r - 0.5;
    float v = texture2D(uV, uv).r - 0.5;
    vec3 rgb = vec3(yy + 1.596 * v, yy - 0.391 * u - 0.813 * v, yy + 2.018 * u);

    // --- Ajustes de imagen: luz, contraste, color, temperatura y balance de blancos ---
    rgb = (rgb - 0.5) * (1.0 + uContraste * 0.9) + 0.5;
    rgb += uLuz * 0.35;
    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    rgb = mix(vec3(luma), rgb, 1.0 + uColor);
    // Balance de blancos: verde (+) o magenta (-), conservando el brillo.
    float wbTinte = uWb * 0.20;
    rgb += vec3(-wbTinte * 0.55, wbTinte, -wbTinte * 0.55);
    // Viñeta: oscurece las orillas según la distancia al centro.
    float dv = length(vQ - vec2(0.5)) * 1.4142;
    rgb *= 1.0 - uVineta * smoothstep(0.30, 1.0, dv);
    // Posterización: reduce los niveles de color (efecto de pocos tonos).
    if (uPoster > 0.001) {
        float np = clamp(uPoster, 0.0, 1.0);
        // Escala exponencial: el efecto se nota desde el primer tercio del recorrido.
        float nv = 255.0 * pow(0.0078, np);
        rgb = floor(rgb * nv + 0.5) / nv;
    }
    vec2 p = (vQ - 0.5) * 2.0 * uHalfPx;
    float d = sdRound(p, uHalfPx, uRadius);
    float a = 1.0 - smoothstep(-1.5, 0.0, d);
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), uOpacity * a);
}
"""

private const val FRAG_RGBA = """
precision mediump float;
varying vec2 vQ;
uniform sampler2D uTex;
uniform float uOpacity;
uniform float uRadius;
uniform vec2 uHalfPx;

float sdRound(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + vec2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - r;
}

void main() {
    vec4 c = texture2D(uTex, vQ);
    vec2 p = (vQ - 0.5) * 2.0 * uHalfPx;
    float d = sdRound(p, uHalfPx, uRadius);
    float a = 1.0 - smoothstep(-1.5, 0.0, d);
    gl_FragColor = vec4(c.rgb, c.a * uOpacity * a);
}
"""

private const val FRAG_SOLID = """
precision mediump float;
uniform vec4 uColor;
void main() {
    gl_FragColor = uColor;
}
"""

/** Programa de dibujo de video I420 con esquinas redondeadas y recorte tipo cover. */
class YuvProgram {
    private val id = Gl.link(VERT_SHADER, FRAG_YUV)
    private val aQuad = GLES20.glGetAttribLocation(id, "aQuad")
    private val uMvp = GLES20.glGetUniformLocation(id, "uMvp")
    private val uY = GLES20.glGetUniformLocation(id, "uY")
    private val uU = GLES20.glGetUniformLocation(id, "uU")
    private val uV = GLES20.glGetUniformLocation(id, "uV")
    private val uUvOff = GLES20.glGetUniformLocation(id, "uUvOff")
    private val uUvScale = GLES20.glGetUniformLocation(id, "uUvScale")
    private val uRot = GLES20.glGetUniformLocation(id, "uRot")
    private val uMirror = GLES20.glGetUniformLocation(id, "uMirror")
    private val uOpacity = GLES20.glGetUniformLocation(id, "uOpacity")
    private val uRadius = GLES20.glGetUniformLocation(id, "uRadius")
    private val uHalfPx = GLES20.glGetUniformLocation(id, "uHalfPx")
    private val uTexel = GLES20.glGetUniformLocation(id, "uTexel")
    private val uLuz = GLES20.glGetUniformLocation(id, "uLuz")
    private val uContraste = GLES20.glGetUniformLocation(id, "uContraste")
    private val uWb = GLES20.glGetUniformLocation(id, "uWb")
    private val uColor = GLES20.glGetUniformLocation(id, "uColor")
    private val uVineta = GLES20.glGetUniformLocation(id, "uVineta")
    private val uPoster = GLES20.glGetUniformLocation(id, "uPoster")

    fun draw(
        texture: SourceTexture,
        mvp: FloatArray,
        uvOffX: Float, uvOffY: Float,
        uvScaleX: Float, uvScaleY: Float,
        mirror: Boolean,
        opacity: Float,
        radiusPx: Float,
        halfW: Float, halfH: Float,
        luz: Float = 0f,
        contraste: Float = 0f,
        wb: Float = 0f,
        color: Float = 0f,
        vineta: Float = 0f,
        poster: Float = 0f
    ) {
        GLES20.glUseProgram(id)
        GLES20.glEnableVertexAttribArray(aQuad)
        Gl.quad.position(0)
        GLES20.glVertexAttribPointer(aQuad, 2, GLES20.GL_FLOAT, false, 0, Gl.quad)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.texY)
        GLES20.glUniform1i(uY, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.texU)
        GLES20.glUniform1i(uU, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.texV)
        GLES20.glUniform1i(uV, 2)

        GLES20.glUniform1f(uLuz, luz)
        GLES20.glUniform1f(uContraste, contraste)
        GLES20.glUniform1f(uWb, wb)
        GLES20.glUniform1f(uColor, color)
        GLES20.glUniform1f(uVineta, vineta)
        GLES20.glUniform1f(uPoster, poster)
        GLES20.glUniform2f(uUvOff, uvOffX, uvOffY)
        GLES20.glUniform2f(uUvScale, uvScaleX, uvScaleY)
        GLES20.glUniform1i(uRot, texture.rotation / 90 % 4)
        GLES20.glUniform1f(uMirror, if (mirror) 1f else 0f)
        GLES20.glUniform1f(uOpacity, opacity)
        GLES20.glUniform1f(uRadius, radiusPx)
        GLES20.glUniform2f(uHalfPx, halfW, halfH)
        GLES20.glUniform2f(uTexel, 1f / texture.texW, 1f / texture.texH)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aQuad)
    }

    fun release() = GLES20.glDeleteProgram(id)
}

/** Programa para imágenes RGBA (overlays). */
class RgbaProgram {
    private val id = Gl.link(VERT_SHADER, FRAG_RGBA)
    private val aQuad = GLES20.glGetAttribLocation(id, "aQuad")
    private val uMvp = GLES20.glGetUniformLocation(id, "uMvp")
    private val uTex = GLES20.glGetUniformLocation(id, "uTex")
    private val uOpacity = GLES20.glGetUniformLocation(id, "uOpacity")
    private val uRadius = GLES20.glGetUniformLocation(id, "uRadius")
    private val uHalfPx = GLES20.glGetUniformLocation(id, "uHalfPx")

    fun draw(mvp: FloatArray, textureId: Int, opacity: Float, radiusPx: Float, halfW: Float, halfH: Float) {
        GLES20.glUseProgram(id)
        GLES20.glEnableVertexAttribArray(aQuad)
        Gl.quad.position(0)
        GLES20.glVertexAttribPointer(aQuad, 2, GLES20.GL_FLOAT, false, 0, Gl.quad)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1f(uOpacity, opacity)
        GLES20.glUniform1f(uRadius, radiusPx)
        GLES20.glUniform2f(uHalfPx, halfW, halfH)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aQuad)
    }

    fun release() = GLES20.glDeleteProgram(id)
}

/** Color plano: fondo, borde de selección. */
class SolidProgram {
    private val id = Gl.link(VERT_SHADER, FRAG_SOLID)
    private val aQuad = GLES20.glGetAttribLocation(id, "aQuad")
    private val uMvp = GLES20.glGetUniformLocation(id, "uMvp")
    private val uColor = GLES20.glGetUniformLocation(id, "uColor")

    fun draw(mvp: FloatArray, r: Float, g: Float, b: Float, a: Float) {
        GLES20.glUseProgram(id)
        GLES20.glEnableVertexAttribArray(aQuad)
        Gl.quad.position(0)
        GLES20.glVertexAttribPointer(aQuad, 2, GLES20.GL_FLOAT, false, 0, Gl.quad)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(uColor, r, g, b, a)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aQuad)
    }

    fun release() = GLES20.glDeleteProgram(id)
}

/** Texturas Y/U/V de una cámara. Sube cuadros I420 al GPU. */
class SourceTexture {
    var texY = 0
    var texU = 0
    var texV = 0
    var texW = 0
    var texH = 0
    var rotation = 0

    private var lastW = -1
    private var lastH = -1
    var hasData = false
        private set

    private var packBuf: ByteBuffer? = null
    private val allocated = BooleanArray(1)

    fun ensure(): Boolean {
        if (allocated[0]) return true
        val ids = IntArray(3)
        GLES20.glGenTextures(3, ids, 0)
        texY = ids[0]; texU = ids[1]; texV = ids[2]
        for (t in ids) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        allocated[0] = true
        return true
    }

    fun upload(frame: VideoFrame) {
        ensure()
        val src = frame.buffer
        val i420 = (src as? VideoFrame.I420Buffer) ?: src.toI420() ?: return
        try {
            val w = i420.width
            val h = i420.height
            if (w != texW || h != texH) {
                texW = w
                texH = h
            }
            uploadPlane(texY, i420.dataY, i420.strideY, w, h)
            uploadPlane(texU, i420.dataU, i420.strideU, w / 2, h / 2)
            uploadPlane(texV, i420.dataV, i420.strideV, w / 2, h / 2)
            // La rotación del cuadro la aplica el shader (uRot); el decodificador NO
            // reorienta los píxeles, así que hay que quedarse con el metadato.
            val rot = ((frame.rotation % 360) + 360) % 360
            rotation = rot
            if (w != lastW || h != lastH) {
                lastW = w
                lastH = h
                com.jfrmedia.switcher.core.util.L.i("gl", "cuadro ${w}x$h")
            }
            hasData = true
        } finally {
            if (i420 !== src) i420.release()
        }
    }

    private fun uploadPlane(tex: Int, data: ByteBuffer, stride: Int, w: Int, h: Int) {
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        val packed = if (stride == w) null else pack(data, stride, w, h)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, packed ?: data
        )
    }

    private fun pack(data: ByteBuffer, stride: Int, w: Int, h: Int): ByteBuffer {
        val need = w * h
        var pb = packBuf
        if (pb == null || pb.capacity() < need) {
            pb = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
            packBuf = pb
        }
        pb.clear()
        val row = ByteArray(stride)
        data.position(0)
        for (y in 0 until h) {
            val remain = data.remaining().coerceAtMost(stride)
            data.get(row, 0, remain)
            if (remain < stride) {
                for (i in remain until stride) row[i] = 0
            }
            pb.put(row, 0, w)
        }
        data.position(0)
        pb.position(0)
        pb.limit(need)
        return pb
    }

    fun release() {
        if (!allocated[0]) return
        GLES20.glDeleteTextures(3, intArrayOf(texY, texU, texV), 0)
        allocated[0] = false
        hasData = false
    }
}

/** Textura RGBA para overlays de imagen. */
class ImageTexture(val bitmap: Bitmap) {
    var id = 0
        private set

    fun ensure(): Boolean {
        if (id != 0) return true
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return true
    }

    fun release() {
        if (id != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            id = 0
        }
    }
}

/**
 * Matriz que lleva el cuadro unidad (0..1) al rectángulo normalizado del lienzo.
 *
 * En Modo Studio dibujan **dos** compositores a la vez (preview y programa), cada uno en su hilo,
 * así que la matriz temporal y la bandera de inversión van **por hilo**: si se comparten, un hilo
 * cambia los valores del otro a media pasada y la imagen parpadea o se voltea.
 */
object Mvp {
    private val tmpPorHilo = ThreadLocal.withInitial { FloatArray(16) }
    private val invertirPorHilo = ThreadLocal.withInitial { false }

    /**
     * Al dibujar en la salida del emisor (SurfaceTexture de RootEncoder) hay que invertir
     * la Y: esa superficie trabaja con Y hacia arriba y el cuadro saldría volteado.
     */
    var invertirY: Boolean
        get() = invertirPorHilo.get() == true
        set(value) = invertirPorHilo.set(value)

    fun forRect(out: FloatArray, x0: Float, y0: Float, x1: Float, y1: Float) {
        val tmp = tmpPorHilo.get() ?: FloatArray(16)
        Matrix.setIdentityM(tmp, 0)
        val ty = 1f - y0 * 2f
        val sy = -(y1 - y0) * 2f
        if (invertirY) {
            Matrix.translateM(tmp, 0, x0 * 2f - 1f, -ty, 0f)
            Matrix.scaleM(tmp, 0, (x1 - x0) * 2f, -sy, 1f)
        } else {
            Matrix.translateM(tmp, 0, x0 * 2f - 1f, ty, 0f)
            Matrix.scaleM(tmp, 0, (x1 - x0) * 2f, sy, 1f)
        }
        System.arraycopy(tmp, 0, out, 0, 16)
    }
}

private const val FRAG_OES = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vQ;
uniform samplerExternalOES uTex;
uniform mat4 uTexMat;
uniform float uOpacity;
void main() {
    // La matriz de la SurfaceTexture espera Y hacia arriba; el lienzo usa Y hacia
    // abajo, así que se invierte antes de aplicarla o el video sale volteado.
    vec2 base = vec2(vQ.x, 1.0 - vQ.y);
    vec4 q = uTexMat * vec4(base, 0.0, 1.0);
    vec4 c = texture2D(uTex, q.xy);
    gl_FragColor = vec4(c.rgb, c.a * uOpacity);
}
"""

/** Programa para video reproducido con MediaPlayer (textura externa OES). */
class OesProgram {
    private val id = Gl.link(VERT_SHADER, FRAG_OES)
    private val aQuad = GLES20.glGetAttribLocation(id, "aQuad")
    private val uMvp = GLES20.glGetUniformLocation(id, "uMvp")
    private val uTex = GLES20.glGetUniformLocation(id, "uTex")
    private val uTexMat = GLES20.glGetUniformLocation(id, "uTexMat")
    private val uOpacity = GLES20.glGetUniformLocation(id, "uOpacity")

    fun draw(mvp: FloatArray, textureId: Int, texMat: FloatArray, opacity: Float) {
        GLES20.glUseProgram(id)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMat, 1, false, texMat, 0)
        GLES20.glUniform1f(uOpacity, opacity)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(0x8D65, textureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glEnableVertexAttribArray(aQuad)
        GLES20.glVertexAttribPointer(aQuad, 2, GLES20.GL_FLOAT, false, 0, Gl.quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aQuad)
        GLES20.glBindTexture(0x8D65, 0)
    }
}
