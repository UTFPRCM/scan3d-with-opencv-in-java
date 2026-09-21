---
name: scan3d-legacy-run-port
description: Como compilar, executar, testar e depurar o projeto scan3d no macOS (Apple Silicon) com Maven, JDK 21 e OpenCV 4.9 via org.openpnp; também o histórico do legado (Eclipse, OpenCV 2.4, Linux) e portabilidade (Linux, Windows, Android). Use quando o pedido for build, execução, testes práticos, calibração ou erro de biblioteca nativa.
---

# Executar e evoluir o scan3d

## Ambiente (macOS arm64)

```bash
brew install openjdk@21 maven      # o OpenCV NÃO é instalado: vem em org.openpnp:opencv
./scan3d.sh scan --approx-camera   # compila se preciso e executa
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test
```

- O JDK 21 do Homebrew é *keg-only*: `scan3d.sh` define `JAVA_HOME` sozinho se ele não estiver definido. O `mvn` do Homebrew traz também um `openjdk` mais novo; use o 21 (JDKs recentes emitem avisos ao carregar bibliotecas nativas via JNI).
- Carga do OpenCV: `nu.pattern.OpenCV.loadLocally()` (extrai a `.dylib` do jar para uma pasta temporária). Não use `System.loadLibrary` nem `-Djava.library.path`.
- O jar final (`target/scan3d.jar`) tem ~110 MB porque embute as nativas de todos os sistemas. `target/` e `output/` estão no `.gitignore`.
- Linux e Windows usam o mesmo `pom.xml` (o pacote traz as nativas), mas só o macOS arm64 foi testado.

## Comandos

- `scan [--in DIR] [--out DIR] [--camera ARQ] [--approx-camera] [--limit N]`
- `calibrate --in DIR [--pattern 8x6] [--square 50] [--out ARQ]` (não testado com fotos reais)

## Erros comuns

| Sintoma | Causa provável |
| --- | --- |
| `Unable to locate a Java Runtime` | JDK não instalado ou `JAVA_HOME` errado; `brew install openjdk@21` |
| `UnsatisfiedLinkError` | carregou OpenCV fora de `nu.pattern.OpenCV.loadLocally()`, ou versão do JAR e da nativa diferem |
| `AVISO: K parece calibrada para ...` | K não combina com a resolução das fotos; recalibrar ou `--approx-camera` |
| `N de 129 quadros usados` baixo | marcadores fora do quadro/cobertos pelo objeto; veja a lista de descartes |
| `reproj_rms_px` alto | K errada, marcadores trocados ou medidas do modelo (`WIDTH_MM`, `HEIGHT_MM`) diferentes da folha impressa |
| Testes falham em `detectsMarkersInSamplePhoto` | foto de exemplo movida/removida ou limiares alterados |

## Portar

- Android: módulo OpenCV Android (Gradle), `OpenCVLoader.initLocal()`, `Utils.bitmapToMat`. A lógica de `MarkerDetector`, `PoseEstimator`, `ObjectContour` e `PointCloudBuilder` usa só classes `org.opencv.*` e Java puro, então pode ser reaproveitada; `Pipeline`/`Main` (arquivos e CLI) não.
- O legado usava OpenCV 2.4 (`Highgui`, `Core.circle`). Equivalências: `Imgcodecs.imread/imwrite`, `Imgcodecs.IMREAD_COLOR`, `Imgproc.circle/line/putText`, `Imgproc.FONT_HERSHEY_SIMPLEX`. O `lib/` da raiz (jar 2.4 + `.so` Linux) é resquício e não é usado.

## Antes de testes práticos

Recalibrar na resolução das fotos; conferir com régua os 187 × 161 mm entre centros dos marcadores impressos; manter os 4 marcadores visíveis; luz difusa. Detalhes em `README.md` ("Para os testes práticos").
