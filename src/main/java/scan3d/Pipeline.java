package scan3d;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Processa uma pasta de fotos: detecta marcadores, estima a pose, isola o contorno do objeto e gera a nuvem de pontos.
 * <pre>
 * saída/
 *   annotated/*.jpg   foto com marcadores, IDs, moldura do modelo e eixos da pose
 *   contours/*.png    contorno do objeto
 *   poses.csv         rvec, tvec, ângulos e erro de reprojeção por quadro
 *   cloud.ply         nuvem de pontos acumulada (propriedade "frame")
 * </pre>
 */
public final class Pipeline {

    public record Config(Path input, Path output, Path cameraFile, boolean approxCamera, int limit) {}

    public record Summary(int total, int used, List<String> skipped) {}

    private final Config cfg;
    private final MarkerDetector detector = new MarkerDetector();
    private final ObjectContour contour = new ObjectContour();

    public Pipeline(Config cfg) {
        this.cfg = cfg;
    }

    public Summary run() throws IOException {
        Path annotatedDir = Files.createDirectories(cfg.output().resolve("annotated"));
        Path contourDir = Files.createDirectories(cfg.output().resolve("contours"));

        List<Path> images;
        try (Stream<Path> s = Files.list(cfg.input())) {
            images = s.filter(Pipeline::isImage).sorted().limit(cfg.limit() > 0 ? cfg.limit() : Long.MAX_VALUE).toList();
        }

        CameraMatrix camera = null;
        PoseEstimator estimator = null;
        PointCloudBuilder cloudBuilder = null;

        List<PlyWriter.FramePoints> cloud = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int used = 0;

        try (BufferedWriter poses = Files.newBufferedWriter(cfg.output().resolve("poses.csv"))) {
            poses.write("frame,file,tx_mm,ty_mm,tz_mm,rx_deg,ry_deg,rz_deg,reproj_rms_px,points\n");

            for (int i = 0; i < images.size(); i++) {
                Path file = images.get(i);
                String name = file.getFileName().toString();
                String base = name.substring(0, name.lastIndexOf('.'));
                Mat bgr = Imgcodecs.imread(file.toString(), Imgcodecs.IMREAD_COLOR);
                if (bgr.empty()) {
                    skipped.add(name + ": não foi possível ler a imagem");
                    continue;
                }

                if (camera == null) { // a resolução das fotos define como ajustar K
                    camera = loadCamera(bgr);
                    estimator = new PoseEstimator(camera.toMat());
                    cloudBuilder = new PointCloudBuilder(camera.toMat());
                }

                Mat edges = detector.edges(bgr);
                MarkerDetector.Detection det = detector.detect(bgr, edges);
                if (!det.complete()) {
                    skipped.add(String.format("%s: %d triângulo(s) e %d quadrado(s); precisa de 2 e 2",
                            name, det.triangles().size(), det.squares().size()));
                    continue;
                }

                MarkerDetector.MarkerSet markers = detector.order(det);
                Pose pose = estimator.estimate(markers);
                Mat mask = contour.extract(edges, markers);
                List<double[]> points = cloudBuilder.build(mask, pose);
                cloud.add(new PlyWriter.FramePoints(i, points));
                used++;

                poses.write(String.format(Locale.ROOT, "%d,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d%n", i, name,
                        pose.tvec().get(0, 0)[0], pose.tvec().get(1, 0)[0], pose.tvec().get(2, 0)[0],
                        pose.rxDeg(), pose.ryDeg(), pose.rzDeg(), pose.reprojError(), points.size()));

                Imgcodecs.imwrite(annotatedDir.resolve(base + ".jpg").toString(), annotate(bgr, markers, pose, estimator));
                Imgcodecs.imwrite(contourDir.resolve(base + ".png").toString(), mask);
            }
        }

        PlyWriter.write(cfg.output().resolve("cloud.ply"), cloud);
        return new Summary(images.size(), used, skipped);
    }

    private CameraMatrix loadCamera(Mat firstImage) throws IOException {
        org.opencv.core.Size size = firstImage.size();
        CameraMatrix camera;
        if (cfg.approxCamera()) {
            camera = CameraMatrix.approximate(size);
            System.out.printf(Locale.ROOT, "Câmera aproximada (sem calibração): f = %.0f px, centro (%.1f, %.1f)%n",
                    camera.fx(), camera.cx(), camera.cy());
        } else {
            camera = CameraMatrix.load(cfg.cameraFile()).forImage(size);
            String warning = camera.check(size);
            if (warning != null) System.out.println("AVISO: " + warning);
        }
        return camera;
    }

    private static Mat annotate(Mat bgr, MarkerDetector.MarkerSet m, Pose pose, PoseEstimator estimator) {
        Mat img = bgr.clone();
        String[] labels = {"T0", "S0", "T1", "S1"};
        Point[] pts = m.asArray();
        for (int i = 0; i < 4; i++) {
            Imgproc.circle(img, pts[i], 4, new Scalar(0, 0, 255), -1);
            Imgproc.putText(img, labels[i], new Point(pts[i].x + 6, pts[i].y - 6), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5,
                    new Scalar(255, 0, 0), 1);
        }
        // moldura do modelo reprojetada: tri0 → sq0 → sq1 → tri1
        Point[] frame = estimator.project(List.of(PoseEstimator.MODEL.get(0), PoseEstimator.MODEL.get(1),
                PoseEstimator.MODEL.get(3), PoseEstimator.MODEL.get(2)), pose);
        for (int i = 0; i < 4; i++) {
            Imgproc.line(img, frame[i], frame[(i + 1) % 4], new Scalar(0, 255, 0), 2);
        }
        // eixos de 50 mm na origem (tri0): X vermelho, Y verde, Z azul (para cima da folha = -Z)
        Point[] axes = estimator.project(List.of(new Point3(0, 0, 0), new Point3(50, 0, 0), new Point3(0, 50, 0),
                new Point3(0, 0, -50)), pose);
        Imgproc.line(img, axes[0], axes[1], new Scalar(0, 0, 255), 2);
        Imgproc.line(img, axes[0], axes[2], new Scalar(0, 255, 0), 2);
        Imgproc.line(img, axes[0], axes[3], new Scalar(255, 0, 0), 2);
        return img;
    }

    private static boolean isImage(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return !n.startsWith(".") && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"));
    }
}
