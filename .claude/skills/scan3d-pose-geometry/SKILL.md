---
name: scan3d-pose-geometry
description: Explica e verifica a geometria do projeto scan3d - modelo pinhole, matriz intrínseca K, extrínsecos [R|t], solvePnP, Rodrigues, homografia do plano da folha e conversão esférica - e aponta onde o código PointsObjectInFrame se afasta da teoria. Use ao interpretar rvec/tvec, ângulos ou pontos 3D.
---

# Geometria de pose do scan3d

Use com `README.md` (seções "Fundamentos" e "De pixels para pontos 3D") e `projects/OpenCv-Java-Example02/src/mainOpenCv/PointsObjectInFrame.java`.

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
| `imagePoints` | centros (`minEnclosingCircle`) de `tri0, sq0, tri1, sq1` |
| `K` | `TextMatrix.txt` |
| `distCoeffs` | zeros |

A correspondência só é válida se a ordem física dos quatro centros bater com a dos pontos 3D. Hoje ela depende da ordem de `findContours`.

## Pixel → plano da folha

Com `Z = 0`, `s·[u v 1]^T = K [r1 r2 t] [X Y 1]^T`. Logo `[X Y 1]^T ∝ inv(K [r1 r2 t]) · [u v 1]^T`. É o que `calcMatrizInversa` faz (matriz 3×3 formada pelas colunas 0, 1 de `R` e por `t`, multiplicada por `K`, invertida com Jama), e o que `pointCloudConstruction` usa com a divisão por `c`.

Consequências para revisar:

1. Só vale para pontos **no plano** `Z = 0`. Um ponto do contorno do objeto está acima da folha; com uma vista só, o raio da câmera é ambíguo e cai num ponto errado do plano. Reconstruir a altura exige mais de um quadro (interseção de raios ou silhuetas).
2. O código usa `a/c` (`rho`, o `X` no plano) só para descartá-lo e usa `b/c` como `raio` esférico. Confirme se essa era a intenção antes de reaproveitar.
3. A conversão `x = r sinθ sinφ; y = r cosθ sinφ; z = r cosφ` usa `θ` (do *roll*) e `φ` (do *pitch*) do quadro como se fossem os ângulos de posição do ponto. É uma convenção do autor; não é consequência direta de `[R|t]`.
4. `calcAngle` usa uma convenção Tait-Bryan própria e ajusta com `180 - yaw` e `360 - theta` (nota: quando `theta < 0`, `360 - theta` soma em vez de subtrair; comparar com `360 + theta`). Antes de confiar nos ângulos, valide contra uma rotação conhecida.

## Checagens numéricas úteis

- **Reprojeção**: `Calib3d.projectPoints` com `rvec`, `tvec`, `K` sobre os 4 `objectPoints` deve cair a poucos pixels dos centros detectados. Erro grande indica ordem errada dos marcadores ou `K` inadequada.
- **Ordem de grandeza de `tvec`**: em mm; a câmera fica dezenas de centímetros da folha, então `|tvec|` esperado na casa de centenas.
- **`K` × resolução**: `cx, cy` devem ficar perto da metade da largura/altura da foto (400, 240 para 800×480). Em `TextMatrix.txt` estão 175.5 e 143.5, o que indica calibração em imagem ~351×287. Se `K` é reescalada por fator `s`, `fx, fy, cx, cy` são multiplicados por `s`, mas só se o campo de visão for o mesmo (mesmo recorte, mesma proporção).
- **Rotação válida**: `R·Rᵀ ≈ I` e `det(R) ≈ 1`.

## Ao propor melhorias

Prefira, nesta ordem: recalibrar; ordenar marcadores; validar por reprojeção; exportar `R`, `t` por quadro; e só então substituir a homografia por interseção de silhuetas (visual hull) ou triangulação. Não "corrija" a fórmula esférica sem antes definir eixos, unidade e convenção de ângulos.
