---
name: scan3d-legacy-run-port
description: Como compilar, executar, testar e depurar o projeto scan3d no macOS (Apple Silicon) com Maven, JDK 21 e OpenCV 4.9 via org.openpnp; também o histórico do legado (Eclipse, OpenCV 2.4, Linux) e portabilidade (Linux, Windows, Android). Use quando o pedido for build, execução, testes práticos, calibração ou erro de biblioteca nativa.
---

# Executar e evoluir o scan3d

## Ambiente (macOS arm64)

```bash
brew install openjdk@21 maven      # o OpenCV NÃO é instalado: vem em org.openpnp:opencv
./scan3d.sh gui                    # janela: Processar imagens / Visualizar
./scan3d.sh scan --approx-camera   # linha de comando; compila se preciso
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test
```

- O JDK 21 do Homebrew é *keg-only*: `scan3d.sh` define `JAVA_HOME` sozinho se ele não estiver definido. O `mvn` do Homebrew traz também um `openjdk` mais novo; use o 21 (JDKs recentes emitem avisos ao carregar bibliotecas nativas via JNI).
- Carga do OpenCV: `nu.pattern.OpenCV.loadLocally()` (extrai a `.dylib` do jar para uma pasta temporária). Não use `System.loadLibrary` nem `-Djava.library.path`.
- O jar final (`target/scan3d.jar`) tem ~120 MB: nativas do OpenCV de todos os sistemas e do JavaFX só do sistema que compilou (o jar da GUI não é portável entre sistemas; recompile). `target/` e `output/` estão no `.gitignore`.
- Linux e Windows usam o mesmo `pom.xml` (o Maven escolhe o classificador do JavaFX e traz as nativas do OpenCV), mas só o macOS arm64 foi testado.
- JavaFX 21 roda pelo classpath (jar único) e imprime `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'`: aviso inofensivo. `Main` não estende `Application` de propósito (senão o `java -jar` falha).

## Comandos

- `scan [--in DIR] [--out DIR] [--camera ARQ] [--approx-camera] [--limit N] [--min-markers 3|4] [--max-gap N]`
- `gui [--in DIR] [--out DIR]`; modos de teste: `--load`, `--process`, `--screenshot ARQ --tab N` (0 Processamento, 1 Nuvem, 2 Quadros, 3 Contornos; grava PNG da janela e fecha; útil para conferir a GUI sem clicar)
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
| `Not on FX application thread` ao encerrar a GUI | acontece ao encerrar com `Platform.exit()` neste modo de jar único; feche a janela (`stage.close()`) |
| Muitos quadros `low` (3 marcadores) | fotos muito espaçadas: a referência de 4 marcadores fica além de `--max-gap` |

## Portar

- Android: módulo OpenCV Android (Gradle), `OpenCVLoader.initLocal()`, `Utils.bitmapToMat`. A lógica de `MarkerDetector`, `PoseEstimator`, `ObjectContour` e `LaminaCloudBuilder` usa só classes `org.opencv.*` e Java puro, então pode ser reaproveitada; `Pipeline`/`Main` (arquivos e CLI) não.
- O legado usava OpenCV 2.4 (`Highgui`, `Core.circle`). Equivalências: `Imgcodecs.imread/imwrite`, `Imgcodecs.IMREAD_COLOR`, `Imgproc.circle/line/putText`, `Imgproc.FONT_HERSHEY_SIMPLEX`. O `lib/` da raiz (jar 2.4 + `.so` Linux) é resquício e não é usado.

## Antes de testes práticos

Recalibrar na resolução das fotos; conferir com régua os 187 × 161 mm entre centros dos marcadores impressos; manter os 4 marcadores visíveis; luz difusa. Detalhes em `README.md` ("Para os testes práticos").
