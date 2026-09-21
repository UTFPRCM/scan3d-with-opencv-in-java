---
name: scan3d-opencv-java
description: Visão geral e roteiro de investigação do projeto scan3d (reconstrução 3D com OpenCV 4 e Java 21 a partir de fotos 360° com marcador planar). Use para localizar código, entender o pipeline marcador → pose → contorno → nuvem de pontos, interpretar TextMatrix.txt, poses.csv, cloud.ply, in/ e out/, ou decidir o próximo passo.
---

# scan3d

Projeto Maven único (`pom.xml`, pacote `scan3d`). Nasceu de dois protótipos de 2018 em OpenCV 2.4 (Example01 e Example02), unificados com o Example02 como base; o histórico deles está no git. Para a explicação completa, leia `README.md`. Matemática: skill `scan3d-pose-geometry`. Rodar/depurar/evoluir: skill `scan3d-legacy-run-port`.

## Mapa do código (`src/main/java/scan3d`)

| Classe | Papel |
| --- | --- |
| `Main` | CLI: `scan`, `calibrate` e `gui`. |
| `Pipeline` | Em 3 fases: (A) detecta e dá pose aos quadros com 4 marcadores; (B) recupera quadros com 3 marcadores a partir de um vizinho de 4 (em camadas, até 8 saltos); (C) contorno, nuvem e saídas (`annotated/`, `contours/`, `poses.csv`, `cloud.ply`, `skipped.txt`). Aceita um `ProgressListener`. |
| `MarkerDetector` | Bordas → contornos → triângulos (3 vértices) e quadrados (4) com teste escuro/claro → centroides → **ordem por orientação** (`order`). |
| `PoseEstimator` / `Pose` | `solvePnP` com 4 marcadores (modelo 187 × 161 mm, Z = 0); `estimateFromThree` (P3P, testa hipóteses de identidade e escolhe a mais próxima de uma pose de referência); ângulos Z-Y-X; erro de reprojeção (NaN com 3). `Pose.markers` = 4 ou 3 (`lowConfidence`). |
| `ObjectContour` | Contorno do objeto: `Canny` 20/100 direto do cinza (bordas do código antigo, não as dos marcadores), recortado pelo volume do objeto (retângulo dos marcadores encolhido 25 mm e esticado 250 mm para cima) reprojetado com a pose; fica o componente de maior caixa envolvente, com banda fina. Saída fina, não preenchida. Reproduz 99% do `out/` de 2018 (teste). Limite: a borda de trás da folha ainda entra em alguns quadros. |
| `LaminaCloudBuilder` | Nuvem por lâminas: cada contorno vira um plano vertical pelo eixo Z (centro da folha), girado pelo azimute da câmera (relativo ao 1º quadro). Cada pixel é lançado como raio e intersectado com o plano (resolve perspectiva e escala). Só os extremos esquerdo e direito por altura, com filtro de picos. `add()` por quadro e `finish()` estima o centro do objeto (mínimos quadrados robustos) e o recentra no eixo. Unidade: pixels de referência (px do 1º quadro); a escala fica no cabeçalho do PLY. |
| `PlyWriter` / `PlyReader` | PLY ASCII com propriedades `frame` e `markers` e um comentário de cabeçalho com a unidade/escala; leitura em arrays paralelos. |
| `gui/GuiApp` | Janela JavaFX: "Processar imagens" (Task em segundo plano), "Visualizar", abas Processamento, Nuvem de pontos, Quadros e Contornos. |
| `gui/CloudView` | Visualizador próprio: projeção em buffer de pixels com z-buffer, sem OpenGL. Cor por altura, quadro ou confiança. |
| `gui/FramesPane` | Lista de quadros (verde 4, laranja 3, vermelho ignorado) e foto anotada. |
| `gui/ContoursPane` | Aba "Contornos": contorno de cada quadro, sobre a foto (vermelho) ou sozinho; lê `contours/` e `poses.csv`. |
| `CameraMatrix` | Lê/grava `TextMatrix.txt` (9 valores, ou 11 com resolução); `approximate`, `forImage`, `check`. |
| `Calibrator` | Calibração com tabuleiro de xadrez (portado de `CalibChessBoard.java~`). |

Dados: `in/800x480 com objeto/` (129 fotos de teste), `out/` (**10 saídas históricas de 2018**, não geradas pelo código atual), `output/` (saída atual, ignorada pelo git), `docs/img/` (figuras; várias são de terceiros e ilustram o conceito), `lib/` (legado OpenCV 2.4, sem uso), `TextMatrix.txt` (K de 2018).

## Fatos que costumam confundir

- `TextMatrix.txt` é a matriz **intrínseca** K, calibrada para ~351 × 287 px. As fotos são 800 × 480, então o programa avisa; com `--approx-camera` o erro de reprojeção mediano é ~1,2 px, sem ele ~26 px.
- `rvec`/`tvec` são extrínsecos **por quadro**; ficam em `poses.csv` (`tx,ty,tz` em mm; `rx,ry,rz` em graus).
- Quadros com 4 marcadores dão a pose normal (72 de 129). Com 3 marcadores a pose é recuperada e marcada como **confiança menor** (44 quadros; coluna `markers`, moldura laranja). Com menos, o quadro é **ignorado**, com motivo em `skipped.txt` (13). Costuma faltar o triângulo de trás, escondido pelo objeto.
- O contorno do objeto é contínuo na maioria dos quadros (~1,4 mil pixels). A nuvem sai da montagem por **lâminas**: forma do pote, raio estável (dispersão de 1 a 2 mm), 296° de cobertura em volta do eixo nas fotos de exemplo. É exata para objetos de revolução e aproximada para os demais; o método geral seria o visual hull.
- `out/` são os 10 melhores quadros de 2018 (início da sequência), não o caso típico. Vieram de bordas `Canny(cinza,100,20)` + dilatação 2×2, não das bordas do Example02 (que servem aos marcadores).
- JOGL e Jama foram removidos de propósito. A visualização é a da GUI JavaFX (`gui/CloudView`), que lê `cloud.ply`.

## Roteiro de investigação

1. Rode `./scan3d.sh scan --approx-camera --out output/x` (ou `./scan3d.sh gui`) e abra `output/x/annotated/`: os eixos e a moldura devem ficar sobre a folha (verde = 4 marcadores, laranja = 3).
2. Leia `poses.csv`: `reproj_rms_px` alto ⇒ K ruim ou marcadores trocados; `points` = 0 ⇒ contorno não encontrado; em quadros `low`, `ref_dist_mm` alto ⇒ pose menos confiável.
3. Para um quadro descartado, veja a mensagem (`N triângulo(s) e M quadrado(s)`) e desenhe o que o detector viu (formas e círculos) antes de mexer em limiares.
4. Só depois olhe `cloud.ply` (MeshLab/CloudCompare).

## Ordem segura para evoluir

1. Recalibrar na resolução real e usar `distCoeffs`.
2. Segmentar o objeto contra o papel branco (silhueta preenchida).
3. Visual hull (interseção das silhuetas) para objetos que não são de revolução.
4. Escolher a melhor combinação quando há uma forma a mais (falso positivo).
Mude uma etapa por vez e reconfira `reproj_rms_px`, a contagem de quadros usados e `mvn test`.
