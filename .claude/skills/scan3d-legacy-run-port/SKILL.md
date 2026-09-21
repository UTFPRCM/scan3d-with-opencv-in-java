---
name: scan3d-legacy-run-port
description: Como executar o protótipo scan3d legado (Eclipse, OpenCV 2.4, Linux) e como portá-lo para OpenCV 3/4, macOS ou Android. Use quando o pedido for compilar, rodar, corrigir classpath/biblioteca nativa, ou migrar a API.
---

# Rodar e portar o scan3d

## Estado do build

- Sem `pom.xml`, `build.gradle` ou testes. Projetos Eclipse (`.project`, `.classpath`).
- `.classpath` com caminhos absolutos de `/home/jose/workspace/...`; ajuste ou recrie o Build Path.
- `lib/opencv-2413.jar` + `lib/libopencv_java2413.so` (ELF Linux x86-64). O `.so` **não carrega** em macOS, Windows nem Android.
- Example02 usa `projects/OpenCv-Java-Example02/lib/Jama-1.0.3.jar`. As classes JOGL (`MainCvinGL`, `OpenCVImageInGL`, `OpenCVGLTexture`) precisam de `jogl-all`/`gluegen-rt`, que **não estão** no repositório; sem eles, exclua essas classes do build.
- `*.java~` são backups do editor e não fazem parte do build.

## Executar (Linux, ambiente histórico)

1. Java 8+.
2. Build Path: `lib/opencv-2413.jar` (+ Jama no Example02).
3. Native library location: `lib/` (ou `-Djava.library.path=<lib>`).
4. `MainActivity.test0()`: trocar o caminho fixo por uma pasta com fotos `.jpg` e `TextMatrix.txt`.
5. Rodar `mainOpenCv.MainActivity`; a saída é o total de quadrados/triângulos e, no Example01, fotos em `<pasta>/out/`.

Linha de comando equivalente (sem Eclipse), a partir da raiz, para o Example01:

```bash
javac -cp lib/opencv-2413.jar -d /tmp/ex01 projects/OpenCv-Java-Example01/src/mainOpenCv/*.java
java -Djava.library.path=lib -cp /tmp/ex01:lib/opencv-2413.jar mainOpenCv.MainActivity
```

(Não testado em Linux; edite o caminho em `test0()` antes.)

## Portar para OpenCV 3/4

| 2.4 | 3/4 |
| --- | --- |
| `org.opencv.highgui.Highgui` | `org.opencv.imgcodecs.Imgcodecs` |
| `Highgui.imread/imwrite` | `Imgcodecs.imread/imwrite` |
| `Highgui.CV_LOAD_IMAGE_COLOR` | `Imgcodecs.IMREAD_COLOR` |
| `Core.circle/line/putText` | `Imgproc.circle/line/putText` |
| `Core.FONT_HERSHEY_SIMPLEX` | `Imgproc.FONT_HERSHEY_SIMPLEX` |

`Calib3d.solvePnP`, `Rodrigues`, `projectPoints`, `findContours`, `approxPolyDP`, `minEnclosingCircle` e `minAreaRect` mantêm o uso.  Confira as assinaturas na versão escolhida ao compilar.

## Portar para macOS ou Android

- macOS: obter OpenCV Java (`opencv-XYZ.jar` + `libopencv_javaXYZ.dylib`) da versão escolhida, via build do OpenCV com `BUILD_JAVA=ON` ou pacote do gerenciador (por exemplo, Homebrew). Referenciar com `-Djava.library.path`.
- Android: usar o módulo OpenCV Android (Gradle) e `OpenCVLoader.initLocal()`; converter `Bitmap`↔`Mat` com `Utils`. Trocar o loop de arquivos por callbacks de câmera e mover `TextMatrix.txt` para `assets/`.
- Alternativa pragmática: manter o processamento offline em Python/OpenCV para validar a matemática e portar depois.

## Antes de qualquer porte

Corrija os problemas de lógica listados no `README.md` (validação dos 4 marcadores, ordem, `K` na resolução correta): portar código com esses erros só muda o lugar onde eles falham.
