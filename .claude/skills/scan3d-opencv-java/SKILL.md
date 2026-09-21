---
name: scan3d-opencv-java
description: Visão geral e roteiro de investigação do protótipo legado de reconstrução 3D em Java/OpenCV 2.4 (Example01 e Example02). Use para localizar código, entender o pipeline marcador → pose → contorno → nuvem de pontos, interpretar TextMatrix.txt, in/ e out/, e separar o que está implementado do que está comentado.
---

# Scan3D OpenCV Java

Repositório de um protótipo de 2018: reconstruir um objeto em 3D a partir de fotos ao redor dele (360°), usando um marcador impresso numa folha A4. Tudo é orientado a arquivos (lê JPGs de uma pasta); não há testes nem build automatizado. **Regra principal: separe o que roda, o que está comentado e o que era só intenção.**

Para a explicação completa, leia `README.md`. Para matemática, use a skill `scan3d-pose-geometry`; para rodar ou portar, `scan3d-legacy-run-port`.

## Mapa do código

| Arquivo | Papel |
| --- | --- |
| `projects/OpenCv-Java-Example01/src/mainOpenCv/MainActivity.java` | Detecta 2 quadrados + 2 triângulos (exige exatamente 2+2); grava foto anotada em `out/`. `project3d` (cubo com `projectPoints`) está comentado no fluxo. |
| `projects/OpenCv-Java-Example02/src/mainOpenCv/MainActivity.java` | Filtros diferentes, tolerância maior, `calcSolvepnp` ativo; `maskImage` e todos os `imwrite` **comentados**. |
| `.../Example02/.../PointsObjectInFrame.java` | Inversa de `K[r1 r2 t]`, `calcAngle`, `pointCloudConstruction` (só imprime N×3). Nada o alimenta hoje. |
| `.../Example02/.../MainCvinGL`, `OpenCVImageInGL`, `OpenCVGLTexture` | Experimento JOGL: desenha linhas/eixos de teste, não recebe nuvem. |
| `.../src/mainOpenCv/CalibChessBoard.java~` | Único vestígio da calibração (tabuleiro 8×6, 50 mm, `CALIB_FIX_PRINCIPAL_POINT`). É backup de editor, não compila no projeto. |
| `TextMatrix.txt` | Matriz **intrínseca** `fx,0,cx,0,fy,cy,0,0,1`. |
| `in/800x480 com objeto/` | 129 fotos 800×480 de um pote sobre a folha. |
| `out/` | 10 `*.matMask.jpg`: contorno fino do objeto (não é máscara preenchida). |
| `docs/img/` | Figuras; algumas são de terceiros e ilustram o conceito (`mascara.png`, `escala-de-cinza-contornos.png`, `detectacao-objeto-retangular.png`, `nuvem-de-pontos.jpg`, `objeto-nuvem-de-pontos-modelo-3d.png`), **não** são saídas deste código. |
| `lib/` | `opencv-2413.jar`, `libopencv_java2413.so` (ELF Linux). |

## Fluxo real (Example02)

`processImages` → `imread` → `imgproc1` (cinza → adaptiveThreshold MEAN → Canny → dilate) → `findObjects` (contornos → `approxPolyDP` → classifica por área) → `storesCentroid` → `calcSolvepnp`. Aí termina: `rvec`/`tvec` são locais e o resultado é descartado.

## Roteiro de investigação

1. Identifique o exemplo e o ponto de entrada (`mainOpenCv.MainActivity`).
2. Confirme a pasta de entrada: `test0()` tem o caminho fixo `/home/jose/Documentos/Opencv` e o `TextMatrix.txt` é lido dessa mesma pasta.
3. Antes de interpretar pose, confira que há exatamente 4 formas e a ordem dos pontos (`tri0, sq0, tri1, sq1` no Ex.02; outra ordem no Ex.01).
4. Trate `TextMatrix.txt` como `K` (intrínseco) e `rvec`/`tvec` por quadro como extrínseco. Os nomes no código estão trocados: `paramExtrinseco(cameraMat)` recebe `K`; `paramIntrinseco(rvec, tvec)` recebe extrínsecos.
5. Para nuvem de pontos, confirme se `maskImage`, `paramIntrinseco`, `paramExtrinseco` estão descomentados e se `mask`, `rvec`, `tvec`, `theta`, `phi` foram preenchidos antes de `pointCloudConstruction`.
6. Verifique se a saída pedida é de fato persistida: a nuvem só é impressa com `System.out.println`.

## Armadilhas confirmadas

- `K` foi calibrada em imagem de ~351×287 (`cx=175.5`, `cy=143.5`, ponto principal fixo no centro); as fotos são 800×480. Recalibrar ou reescalar antes de confiar em valores numéricos.
- Ex.02: `listSquares.size() >= 0 && listTriangles.size() >= 0` é sempre verdadeiro; `calcSolvepnp` faz `.get(1)` e quebra com menos de 2+2 formas.
- Ordem dos marcadores vem da enumeração de contornos, sem garantia semântica.
- Ex.01 usa modelo 161×187 mm e Ex.02 187×161 mm; medido no gabarito, 187 mm é a distância horizontal.
- Ex.01 `project3d` mistura `centerSquare.get(0).center.x` com `centerSquare.get(1).center.y`.
- `pointCloudConstruction` conta com `> 150` e preenche com `> 200`: sobram linhas zeradas.
- `distCoeffs` são zeros.
- API antiga do OpenCV 2.4 (`Highgui`, `Core.circle`); `.so` só serve em Linux x86-64.
- `.classpath` tem caminhos absolutos de `/home/jose/...`; jars JOGL não estão no repositório.

## Ordem segura para estender

1. Receber pastas de entrada/saída por argumento.
2. Validar e ordenar os 4 marcadores.
3. Recalibrar na resolução das fotos.
4. Persistir `rvec`, `tvec`, id do quadro e ângulo.
5. Exportar pontos (`x,y,z,frame`) em CSV/PLY/XYZ, com sistema de coordenadas e unidade documentados.
6. Criar um teste de regressão pequeno com 2 ou 3 fotos de `in/`.
7. Só então visualizar (MeshLab/CloudCompare) ou migrar para Android.
