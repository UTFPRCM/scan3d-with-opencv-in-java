---
name: scan3d-pose-geometry
description: Explica e verifica a geometria do projeto scan3d - modelo pinhole, matriz intrínseca K, extrínsecos [R|t], solvePnP, P3P com 3 marcadores, Rodrigues e a nuvem por lâminas - e a montagem da nuvem por lâminas (azimute da câmera, interseção raio-plano, centro do objeto). Use ao interpretar rvec/tvec, ângulos ou pontos 3D.
---

# Geometria de pose do scan3d

Use com `README.md` (seções "Fundamentos" e "Da silhueta à nuvem 3D (lâminas)") e `src/main/java/scan3d/` (`PoseEstimator`, `LaminaCloudBuilder`, `MarkerDetector.order`).

## Modelo

```text
s [u v 1]^T = K [R | t] [X Y Z 1]^T
```

- **K (intrínseca)**: `fx, fy` (px), `cx, cy` (px). Fixa para uma câmera/lente/resolução. No repositório: `TextMatrix.txt`.
- **[R | t] (extrínseca)**: pose da câmera relativa ao mundo, **por foto**. Vem de `solvePnP`: `rvec` (Rodrigues) e `tvec`. `Calib3d.Rodrigues(rvec)` gera `R`.
- **Mundo**: plano da folha com `Z = 0`, origem no centro de `tri0`, unidade mm (`X` horizontal 187, `Y` vertical 161).
- Semelhança de triângulos, `F = P·D/W`, estima uma distância isolada; não dá pose.

## O que `solvePnP` recebe aqui

| Entrada | Valor |
| --- | --- |
| `objectPoints` | `(0,0,0) (187,0,0) (0,161,0) (187,161,0)` |
| `imagePoints` | centroides (momentos do polígono) de `tri0, sq0, tri1, sq1` |
| `K` | `TextMatrix.txt` |
| `distCoeffs` | zeros |

A correspondência só é válida se a ordem física dos quatro centros bater com a dos pontos 3D. `MarkerDetector.order` garante isso pela orientação: a câmera vê a folha de cima, então o sinal do produto vetorial (eixo triângulo→quadrado) × (eixo entre as duas formas iguais) fixa quem é a "0" e quem é a "1", em qualquer rotação da folha. Usar o centro do círculo mínimo no lugar do centroide desloca o triângulo (cai no meio da hipotenusa) e distorce o modelo em ~9 mm.

## Da silhueta à nuvem: lâminas (`LaminaCloudBuilder`)

Cada contorno é tratado como uma lâmina 2D em pé, no plano vertical que contém o eixo Z (pelo centro da folha, `(W/2, H/2)`), perpendicular à direção horizontal de visada.

- **Ângulo do quadro**: `azimute = atan2(C.y − O.y, C.x − O.x)`, com `C = −Rᵀt` (posição da câmera) e `O` o centro da folha; relativo ao primeiro quadro. Não é `rz` de Euler. Nas fotos de exemplo cobre −86° a +80°.
- **Posição de cada pixel**: raio `dir = Rᵀ·((u−cx)/fx, (v−cy)/fy, 1)` a partir de `C`, intersectado com o plano de normal `n = (cos az, sin az, 0)` por `O`: `λ = n·(O−C)/(n·dir)`. Então `d = (X−O)·t` (deslocamento no plano) e `h = −X.z` (o Z do mundo aponta para a mesa). Isso é exato para pontos do plano e trata perspectiva/inclinação sem fator de escala à parte. (Usar uma escala única por quadro medida na base do eixo superestimava o raio em ~6% no topo com câmera a ~300 mm e elevação de 20°; o teste sintético pegou.)
- **Unidade**: mm × `refScale`, com `refScale` = px/mm do 1º quadro na altura do eixo. É só mudança de unidade constante: a escala fica linear entre quadros. Fica no cabeçalho do PLY.
- **Silhueta**: para cada altura, extremos esquerdo e direito; exato para corpos de revolução. Topo e base ficam de fora (elipses deslocadas pela elevação da câmera, `~R·tan(elevação)`).
- **Centro do objeto**: se o objeto está a `e` do eixo, a média dos extremos em cada quadro vale `e·t`. Estima-se `e` por mínimos quadrados robustos (Huber) e recentra-se. Sem isso o raio varia de ~11 mm com o ângulo (nas fotos de exemplo); com isso, 1 a 2 mm. Requer ângulos suficientemente variados (planos quase paralelos não determinam `e`).
- **Volta**: cada lâmina é bilateral e a vista oposta cai no mesmo plano; azimutes cobrindo A° fecham ~2·A° (`coverageDegrees()`).
- **Limite**: fora de corpos de revolução, o ponto da borda vai para o plano do eixo (não para a profundidade real), então faces planas "estufam". A versão geral é o visual hull.

## Pose com 3 marcadores (P3P)

`PoseEstimator.estimateFromThree`. Com 3 pontos `solvePnP` não serve; usa-se `Calib3d.solveP3P` (`SOLVEPNP_AP3P`), que devolve até 4 soluções. Duas ambiguidades:

1. **Identidade** do marcador que aparece uma só vez (é o "0" ou o "1"?). Decidir só pela geometria da imagem acerta ~71%; por isso as duas hipóteses são testadas.
2. **Solução do P3P.** Descartam-se as fisicamente impossíveis (`tz <= 0` ou câmera abaixo da folha, `C.z >= 0`, pois o Z do mundo aponta para a mesa). Das restantes, vale a mais próxima (posição da câmera) de um quadro vizinho com 4 marcadores.

Os dois marcadores do mesmo tipo são ordenados pela mesma regra de orientação usada com 4. Só se aceita se ficar a até 60 mm × (distância em quadros) e 30° da referência. Não há erro de reprojeção com 3 pontos (ajuste exato). Medido (câmera aproximada, referência = pose de 4 marcadores): 97% dentro de 30 mm/5°, mediana 2,8 mm e 0,5°; encadeamento de até 8 saltos sem acúmulo de erro, porque cada pose é um P3P independente.

## Checagens numéricas úteis

- **Reprojeção**: `poses.csv` já traz `reproj_rms_px` (`projectPoints` sobre os 4 pontos do modelo). Poucos pixels ⇒ pose coerente. Medido nas fotos de exemplo: ~1,2 px (mediana) com `--approx-camera` e ~26 px com o `TextMatrix.txt` de 2018.
- **Ordem de grandeza de `tvec`**: em mm; a câmera fica dezenas de centímetros da folha, então `|tvec|` esperado na casa de centenas.
- **`K` × resolução**: `cx, cy` devem ficar perto da metade da largura/altura da foto (400, 240 para 800×480). Em `TextMatrix.txt` estão 175.5 e 143.5, o que indica calibração em imagem ~351×287 (o ponto principal foi fixado no centro). Se `K` é reescalada por fator `s`, `fx, fy, cx, cy` são multiplicados por `s`, mas só se o campo de visão for o mesmo (mesmo recorte, mesma proporção).
- **Rotação válida**: `R·Rᵀ ≈ I` e `det(R) ≈ 1`.

## Ao propor melhorias

Prefira, nesta ordem: recalibrar; validar por reprojeção; segmentar o objeto (silhueta preenchida); e só então trocar as lâminas pelo visual hull (interseção das silhuetas esticadas) para objetos que não são de revolução. Ao mexer no `LaminaCloudBuilder`, confira com o cilindro sintético do teste (raio igual em todos os ângulos e distâncias).
