package scan3d;

import org.opencv.core.Mat;

/**
 * Pose da câmera em um quadro (extrínsecos). O mundo é o plano da folha (Z = 0), em milímetros.
 *
 * @param rvec        vetor de rotação (Rodrigues) devolvido pelo solvePnP
 * @param tvec        vetor de translação (mm)
 * @param rotation    matriz de rotação 3x3 (CV_64F)
 * @param rxDeg       ângulos de Euler da rotação, convenção Z-Y-X: rx = atan2(R21, R22)
 * @param ryDeg       ry = atan2(-R20, sqrt(R00² + R10²))
 * @param rzDeg       rz = atan2(R10, R00) — é o "roll" usado como theta na nuvem de pontos
 * @param reprojError    erro RMS (pixels) ao reprojetar os 4 marcadores com esta pose; NaN com 3 marcadores
 *                       (3 pontos são ajustados exatamente, então o erro não diz nada). Com 4 marcadores, mas
 *                       {@code lowConfidence} (um veio de {@link MarkerDetector#reclassify}), o erro costuma ficar
 *                       bem acima do normal — o centroide da forma reclassificada se desloca com a oclusão.
 * @param markers        quantos marcadores sustentam a pose: 4 ou 3 (posições, não confiança)
 * @param lowConfidence  true com 3 marcadores, ou com 4 quando um deles veio de {@link MarkerDetector#reclassify}
 *                       (forma cujo tipo foi trocado por oclusão: a posição do centroide pode estar deslocada)
 */
public record Pose(Mat rvec, Mat tvec, Mat rotation, double rxDeg, double ryDeg, double rzDeg, double reprojError,
        int markers, boolean lowConfidence) {
}
