---
name: scan3d-pose-geometry
description: Explica e verifica a geometria do projeto scan3d - modelo pinhole, matriz intrínseca K, extrínsecos [R|t], solvePnP, Rodrigues, homografia do plano da folha e conversão esférica - e aponta onde o PointCloudBuilder se afasta da teoria. Use ao interpretar rvec/tvec, ângulos ou pontos 3D.
---

# Geometria de pose do scan3d

Use com `README.md` (seções "Fundamentos" e "De pixels para pontos 3D") e `src/main/java/scan3d/` (`PoseEstimator`, `PointCloudBuilder`, `MarkerDetector.order`).

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

## Pixel → plano da folha

Com `Z = 0`, `s·[u v 1]^T = K [r1 r2 t] [X Y 1]^T`. Logo `[X Y 1]^T ∝ inv(K [r1 r2 t]) · [u v 1]^T`. É o que `calcMatrizInversa` faz (matriz 3×3 formada pelas colunas 0, 1 de `R` e por `t`, multiplicada por `K`, invertida com Jama), e o que `pointCloudConstruction` usa com a divisão por `c`.

`PointCloudBuilder` implementa isso. Consequências para revisar:

1. Só vale para pontos **no plano** `Z = 0`. Um ponto do contorno do objeto está acima da folha; com uma vista só, o raio da câmera é ambíguo e cai num ponto errado do plano. Reconstruir a altura exige mais de um quadro (interseção de raios ou silhuetas). É por isso que a nuvem atual tem `z` de −791 a +376 mm para um objeto de ~10 cm.
2. O código usa `b/c` (o `Y` no plano) como `raio` esférico e descarta `a/c` (`X`). Confirme se essa era a intenção antes de reaproveitar.
3. A conversão `x = r sinθ sinφ; y = r cosθ sinφ; z = r cosφ` usa `θ = rz` e `φ = |ry|` do quadro como se fossem os ângulos de posição do ponto. É uma convenção do autor original; não é consequência direta de `[R|t]`.
4. Correções já feitas em relação ao original: os ângulos eram passados em graus a `Math.sin/cos`, e `θ < 0` usava `360 - θ` (390° para −30°) em vez de `360 + θ`. Convenção de ângulos: Z-Y-X, `rx = atan2(R21, R22)`, `ry = atan2(-R20, √(R00²+R10²))`, `rz = atan2(R10, R00)`; valide contra uma rotação conhecida antes de confiar.

## Checagens numéricas úteis

- **Reprojeção**: `poses.csv` já traz `reproj_rms_px` (`projectPoints` sobre os 4 pontos do modelo). Poucos pixels ⇒ pose coerente. Medido nas fotos de exemplo: ~1,2 px (mediana) com `--approx-camera` e ~26 px com o `TextMatrix.txt` de 2018.
- **Ordem de grandeza de `tvec`**: em mm; a câmera fica dezenas de centímetros da folha, então `|tvec|` esperado na casa de centenas.
- **`K` × resolução**: `cx, cy` devem ficar perto da metade da largura/altura da foto (400, 240 para 800×480). Em `TextMatrix.txt` estão 175.5 e 143.5, o que indica calibração em imagem ~351×287 (o ponto principal foi fixado no centro). Se `K` é reescalada por fator `s`, `fx, fy, cx, cy` são multiplicados por `s`, mas só se o campo de visão for o mesmo (mesmo recorte, mesma proporção).
- **Rotação válida**: `R·Rᵀ ≈ I` e `det(R) ≈ 1`.

## Ao propor melhorias

Prefira, nesta ordem: recalibrar; ordenar marcadores; validar por reprojeção; exportar `R`, `t` por quadro; e só então substituir a homografia por interseção de silhuetas (visual hull) ou triangulação. Não "corrija" a fórmula esférica sem antes definir eixos, unidade e convenção de ângulos.
