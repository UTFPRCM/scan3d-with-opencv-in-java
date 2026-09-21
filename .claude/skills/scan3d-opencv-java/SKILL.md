---
name: scan3d-opencv-java
description: Visão geral e roteiro de investigação do projeto scan3d (reconstrução 3D com OpenCV 4 e Java 21 a partir de fotos 360° com marcador planar). Use para localizar código, entender o pipeline marcador → pose → contorno → nuvem de pontos, interpretar TextMatrix.txt, poses.csv, cloud.ply, in/ e out/, ou decidir o próximo passo.
---

# scan3d

Projeto Maven único (`pom.xml`, pacote `scan3d`). Nasceu de dois protótipos de 2018 em OpenCV 2.4 (Example01 e Example02), unificados com o Example02 como base; o histórico deles está no git. Para a explicação completa, leia `README.md`. Matemática: skill `scan3d-pose-geometry`. Rodar/depurar/evoluir: skill `scan3d-legacy-run-port`.

## Mapa do código (`src/main/java/scan3d`)

| Classe | Papel |
| --- | --- |
| `Main` | CLI: `scan` e `calibrate`. |
| `Pipeline` | Percorre a pasta, chama as etapas e grava `annotated/`, `contours/`, `poses.csv`, `cloud.ply`. |
| `MarkerDetector` | Bordas → contornos → triângulos (3 vértices) e quadrados (4) com teste escuro/claro → centroides → **ordem por orientação** (`order`). |
| `PoseEstimator` / `Pose` | `solvePnP` com o modelo 187 × 161 mm (Z = 0); ângulos Z-Y-X; erro de reprojeção. |
| `ObjectContour` | Contorno do objeto: contém o centro da folha e nenhum marcador. Saída fina, não preenchida. |
| `PointCloudBuilder` | `inv(K·[r1 r2 t])` + coordenadas esféricas (fórmula do original, com correções de radianos e de θ). |
| `PlyWriter` | PLY ASCII com propriedade `frame`. |
| `CameraMatrix` | Lê/grava `TextMatrix.txt` (9 valores, ou 11 com resolução); `approximate`, `forImage`, `check`. |
| `Calibrator` | Calibração com tabuleiro de xadrez (portado de `CalibChessBoard.java~`). |

Dados: `in/800x480 com objeto/` (129 fotos de teste), `out/` (**10 saídas históricas de 2018**, não geradas pelo código atual), `output/` (saída atual, ignorada pelo git), `docs/img/` (figuras; várias são de terceiros e ilustram o conceito), `lib/` (legado OpenCV 2.4, sem uso), `TextMatrix.txt` (K de 2018).

## Fatos que costumam confundir

- `TextMatrix.txt` é a matriz **intrínseca** K, calibrada para ~351 × 287 px. As fotos são 800 × 480, então o programa avisa; com `--approx-camera` o erro de reprojeção mediano é ~1,2 px, sem ele ~26 px.
- `rvec`/`tvec` são extrínsecos **por quadro**; ficam em `poses.csv` (`tx,ty,tz` em mm; `rx,ry,rz` em graus).
- Quadros com menos de 2 triângulos + 2 quadrados são **descartados** (72 de 129 usados nas fotos de exemplo). Costuma faltar o triângulo de trás, escondido pelo objeto.
- O contorno do objeto é **fragmentado**; a nuvem de pontos não é geometricamente confiável (z de −791 a +376 mm para um pote de ~10 cm).
- JOGL e Jama foram removidos de propósito. A visualização 3D será refeita com outra biblioteca; a entrada esperada é `cloud.ply`.

## Roteiro de investigação

1. Rode `./scan3d.sh scan --approx-camera --out output/x` e abra `output/x/annotated/`: os eixos e a moldura verde devem ficar sobre a folha.
2. Leia `poses.csv`: `reproj_rms_px` alto ⇒ K ruim ou marcadores trocados; `points` = 0 ⇒ contorno não encontrado.
3. Para um quadro descartado, veja a mensagem (`N triângulo(s) e M quadrado(s)`) e desenhe o que o detector viu (formas e círculos) antes de mexer em limiares.
4. Só depois olhe `cloud.ply` (MeshLab/CloudCompare).

## Ordem segura para evoluir

1. Recalibrar na resolução real e usar `distCoeffs`.
2. Segmentar o objeto contra o papel branco (silhueta preenchida).
3. Cruzar silhuetas de vários quadros (*visual hull*) no lugar da homografia de plano único.
4. Só então o visualizador.
Mude uma etapa por vez e reconfira `reproj_rms_px`, a contagem de quadros usados e `mvn test`.
