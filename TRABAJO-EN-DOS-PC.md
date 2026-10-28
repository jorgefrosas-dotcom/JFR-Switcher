# Trabajar en las dos PC sin choques

> JFR Switcher · repo `github.com/jorgefrosas-dotcom/JFR-Switcher`
> Escrito el 27/10/2026, al pasar el proyecto a GitHub.

## Qué cambió (y por qué ya no hay variantes)

| Antes | Ahora |
|---|---|
| Cada PC firmaba el APK con **su** `debug.keystore` → firmas distintas | **Una sola llave compartida**: `keystore/jfr-upload.jks` (las dos PC usan la misma) |
| Para probar había que meter la variante `.dev` (otro paquete) | **Un solo paquete** por app: `com.jfrmedia.switcher.studio` y `…cam` |
| El APK de una PC no se instalaba encima del de la otra (había que desinstalar y se perdían ajustes) | El APK de **cualquier** PC se instala encima: `adb install -r` y listo |
| El código vivía en carpetas distintas de cada PC | Vive en **GitHub**; las dos PC bajan y suben |

**Lo único que hay que hacer una vez por equipo (tablet y celulares):** desinstalar la app vieja
(está firmada con la llave de la otra PC) e instalar el APK nuevo. Después, nunca más.

---

## Rutina de todos los días

### Al empezar a trabajar
Doble clic en **`BAJAR.cmd`** (o `git pull`).
Así te traes lo que hizo la otra PC. **Nunca empieces sin bajar primero.**

### Al terminar
Doble clic en **`SUBIR.cmd`** (o `git add -A && git commit -m "..." && git pull --rebase && git push`).
Guarda el estado y lo sube.

### La regla que evita casi todos los problemas
**No toquen el mismo archivo en las dos PC al mismo tiempo.** Sobre todo `StudioActivity.kt`
(es enorme). Si las dos lo editan, Git pide fusionar a mano y ahí sí hay riesgo de romper algo.

Si `SUBIR.cmd` se queja de **conflicto**: no borres nada, avísame y lo fusionamos juntos.

---

## Publicar una versión nueva (para que la web la muestre)

1. En `gradle.properties`, subir el número:
   ```
   jfr.versionName=0.3.0
   jfr.versionCode=3
   ```
   (`versionCode` **siempre** sube de uno en uno; Android no acepta instalar una versión con el mismo número.)
2. Compilar las dos apps:
   ```powershell
   $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
   .\gradlew.bat :studio:assembleRelease :camera:assembleRelease
   ```
3. Los APK salen en:
   - `studio\build\outputs\apk\release\studio-release.apk`
   - `camera\build\outputs\apk\release\camera-release.apk`
4. En GitHub: **Releases → Draft a new release** → etiqueta `v0.3.0` → adjuntar los dos APK
   **con estos nombres exactos** (la página los busca así):
   - `JFR-Switcher.apk`
   - `JFR-Camera.apk`
5. Publicar. La web `https://jorgefrosas-dotcom.github.io/JFR-Switcher/` apunta siempre al
   **último** release, así que no hay que tocar la página.

---

## La primera vez en cada PC (solo una vez)

```powershell
git clone https://github.com/jorgefrosas-dotcom/JFR-Switcher.git
cd JFR-Switcher
git config --local credential.username jorgefrosas-dotcom
```

Después copiar a esta carpeta los **dos** archivos de la llave: `keystore/jfr-upload.jks` y
`keystore.properties`.

**Ojo con las cuentas:** la PC de trabajo tiene guardada la cuenta `produccionesdangel-CRM` (la del
CRM). Si no se pone `credential.username` como arriba, Git usa esa cuenta y GitHub contesta
*403 Permission denied*. Con esa línea queda atada al repo correcto, sin tocar la otra cuenta.

## La llave de firma (esto es lo más delicado)

- `keystore/jfr-upload.jks` y `keystore.properties` **no están en GitHub** (a propósito:
  el `.gitignore` los excluye). Viven en la carpeta del proyecto de cada PC.
- **Respáldalos** (gestor de contraseñas + una USB). Si se pierden, ya no se pueden publicar
  actualizaciones: habría que desinstalar la app en la tablet y en todos los celulares.
- Para la otra PC: copiar esos **dos** archivos a la misma carpeta del proyecto. Sin ellos
  Gradle no compila y avisa con un mensaje claro.

---

## Cosas que NO se suben nunca
`keystore/`, `keystore.properties`, `local.properties`, carpetas `build/`, `.gradle/`, `.idea/`, APK sueltos.
Todo eso ya está en `.gitignore`.
