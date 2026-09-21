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
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * Processa uma pasta de fotos: detecta marcadores, estima a pose, isola o contorno do objeto e gera a nuvem de pontos.
 * <p>
 * Fases: (A) detecta os marcadores de todas as fotos; quadros com 4 marcadores já têm pose;
 * (B) quadros com 3 marcadores recuperam a pose a partir de um quadro vizinho de 4 marcadores;
 * (C) para cada quadro com pose, extrai o contorno e a nuvem e grava as saídas.
 * Quadros que não chegam a ter pose são ignorados, com o motivo no log e no resumo.
 * <pre>
 * saída/
 *   annotated/*.jpg   foto com marcadores, IDs, moldura do modelo e eixos da pose
 *   contours/*.png    contorno do objeto
 *   poses.csv         pose por quadro (com nº de marcadores, confiança e tipo de contorno: closed/open)
 *   cloud.ply         nuvem de pontos acumulada (propriedades "frame" e "markers")
 *   skipped.txt       um quadro ignorado por linha, com o motivo
 * </pre>
 */
public final class Pipeline {

    /**
     * @param minMarkers mínimo de marcadores por quadro: 4 (só quadros completos) ou 3 (aceita também 3, confiança menor)
     * @param maxGap     distância máxima, em quadros, até o quadro de referência com 4 marcadores usado nos de 3
     */
    public record Config(Path input, Path output, Path cameraFile, boolean approxCamera, int limit, int minMarkers,
            int maxGap) {
        public static final int DEFAULT_MAX_GAP = 3;

        public Config(Path input, Path output, Path cameraFile, boolean approxCamera, int limit) {
            this(input, output, cameraFile, approxCamera, limit, 3, DEFAULT_MAX_GAP);
        }
    }

    public record Summary(int total, int used4, int used3, List<String> skipped) {
        public int used() {
            return used4 + used3;
        }
    }

    /** Estado de um quadro entre as fases. */
    private static final class Frame {
        final int index;
        final Path file;
        MarkerDetector.Detection detection;
        Pose pose;
        Point[] used;          // os 4 pontos do modelo vistos na imagem (null onde o marcador faltou)
        int refFrame = -1;
        int hops;              // 0 = 4 marcadores; n = n-ésimo quadro encadeado a partir de um de 4 marcadores
        double refDistMm = Double.NaN;

        Frame(int index, Path file) {
            this.index = index;
            this.file = file;
        }

        String name() {
            return file.getFileName().toString();
        }
    }

    private static final double MAX_JUMP_MM_PER_FRAME = 60;
    private static final double MAX_ROT_DEG = 30;
    /** Máximo de quadros de 3 marcadores encadeados; a simulação em dados reais foi validada até 8. */
    private static final int MAX_HOPS = 8;

    private final Config cfg;
    private final MarkerDetector detector = new MarkerDetector();
    private final ObjectContour contour = new ObjectContour();

    public Pipeline(Config cfg) {
        this.cfg = cfg;
    }

    public Summary run() throws IOException {
        return run(ProgressListener.NONE);
    }

    public Summary run(ProgressListener listener) throws IOException {
        Path annotatedDir = Files.createDirectories(cfg.output().resolve("annotated"));
        Path contourDir = Files.createDirectories(cfg.output().resolve("contours"));

        List<Path> images;
        try (Stream<Path> s = Files.list(cfg.input())) {
            images = s.filter(Pipeline::isImage).sorted().limit(cfg.limit() > 0 ? cfg.limit() : Long.MAX_VALUE).toList();
        }
        List<String> skipped = new ArrayList<>();
        List<Frame> frames = new ArrayList<>();

        // ---- Fase A: detecção e pose com 4 marcadores
        CameraMatrix camera = null;
        PoseEstimator estimator = null;
        for (int i = 0; i < images.size(); i++) {
            listener.progress("Detectando marcadores", i, images.size());
            Frame f = new Frame(i, images.get(i));
            Mat bgr = Imgcodecs.imread(f.file.toString(), Imgcodecs.IMREAD_COLOR);
            if (bgr.empty()) {
                skip(skipped, listener, f.name() + ": não foi possível ler a imagem");
                continue;
            }
            if (camera == null) { // a resolução das fotos define como ajustar K
                camera = loadCamera(bgr.size(), listener);
                estimator = new PoseEstimator(camera.toMat());
            }
            f.detection = detector.detect(bgr, detector.edges(bgr));
            if (f.detection.complete()) {
                MarkerDetector.MarkerSet markers = detector.order(f.detection);
                f.pose = estimator.estimate(markers);
                f.used = markers.asArray();
            }
            frames.add(f);
        }

        // ---- Fase B: quadros com 3 marcadores
        // Em camadas: a camada 1 usa quadros de 4 marcadores como referência, a 2 usa os resolvidos na 1, e assim por
        // diante. Cada pose vem de um P3P independente (a referência só desempata), então o erro não se acumula.
        List<Frame> pending = new ArrayList<>();
        for (Frame f : frames) {
            if (f.pose != null) continue;
            if (cfg.minMarkers() > 3 || !f.detection.partial()) {
                skip(skipped, listener, f.name() + ": " + counts(f) + "; precisa de "
                        + (cfg.minMarkers() > 3 ? "2 e 2" : "2 e 2, ou 2 e 1, ou 1 e 2"));
            } else {
                pending.add(f);
            }
        }
        for (int hop = 1; hop <= MAX_HOPS && !pending.isEmpty(); hop++) {
            listener.progress("Recuperando quadros com 3 marcadores", hop - 1, MAX_HOPS);
            List<Frame> solved = new ArrayList<>();
            for (Frame f : pending) {
                Frame ref = nearestWithPose(frames, f, cfg.maxGap(), hop - 1);
                if (ref == null) continue;
                int gap = Math.abs(ref.index - f.index);
                PoseEstimator.Recovered r = estimator.estimateFromThree(f.detection, ref.pose,
                        MAX_JUMP_MM_PER_FRAME * gap, MAX_ROT_DEG);
                if (r == null) continue;
                f.pose = r.pose();
                f.used = r.used();
                f.refFrame = ref.index;
                f.refDistMm = r.refDistMm();
                f.hops = hop;
                solved.add(f);
            }
            // só vale na camada seguinte: dentro da mesma camada todos usam o mesmo conjunto de referências
            pending.removeAll(solved);
        }
        for (Frame f : pending) {
            skip(skipped, listener, f.name() + ": " + counts(f) + "; sem referência confiável a até " + cfg.maxGap()
                    + " quadros, ou pose inconsistente com ela");
        }

        // ---- Fase C: contorno, nuvem e saídas
        LaminaCloudBuilder cloudBuilder = estimator == null ? null : new LaminaCloudBuilder(estimator);
        List<Frame> accepted = frames.stream().filter(f -> f.pose != null).toList();
        int used4 = 0, used3 = 0;

        try (BufferedWriter poses = Files.newBufferedWriter(cfg.output().resolve("poses.csv"))) {
            poses.write("frame,file,markers,confidence,tx_mm,ty_mm,tz_mm,rx_deg,ry_deg,rz_deg,reproj_rms_px,"
                    + "ref_frame,ref_hops,ref_dist_mm,points\n");
            for (int n = 0; n < accepted.size(); n++) {
                listener.progress("Gerando contornos e nuvem", n, accepted.size());
                Frame f = accepted.get(n);
                Mat bgr = Imgcodecs.imread(f.file.toString(), Imgcodecs.IMREAD_COLOR);
                String name = f.name();
                String base = name.substring(0, name.lastIndexOf('.'));

                // o objeto fica dentro do retângulo dos marcadores: o contorno só vale nessa região, reprojetada com a pose
                Mat mask = contour.extract(bgr, estimator.project(ObjectContour.volumeCorners(), f.pose));
                int pointCount = cloudBuilder.add(f.index, f.pose.markers(), mask, f.pose);
                if (f.pose.lowConfidence()) used3++;
                else used4++;

                Pose p = f.pose;
                poses.write(String.format(Locale.ROOT, "%d,%s,%d,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%s,%s,%s,%d%n",
                        f.index, csv(name), p.markers(), p.lowConfidence() ? "low" : "high",
                        p.tvec().get(0, 0)[0], p.tvec().get(1, 0)[0], p.tvec().get(2, 0)[0],
                        p.rxDeg(), p.ryDeg(), p.rzDeg(),
                        Double.isNaN(p.reprojError()) ? "" : String.format(Locale.ROOT, "%.2f", p.reprojError()),
                        f.refFrame < 0 ? "" : Integer.toString(f.refFrame),
                        f.refFrame < 0 ? "" : Integer.toString(f.hops),
                        Double.isNaN(f.refDistMm) ? "" : String.format(Locale.ROOT, "%.1f", f.refDistMm),
                        pointCount));

                Imgcodecs.imwrite(annotatedDir.resolve(base + ".jpg").toString(), annotate(bgr, f, estimator));
                Imgcodecs.imwrite(contourDir.resolve(base + ".png").toString(), mask);
            }
        }
        Files.write(cfg.output().resolve("skipped.txt"), skipped);
        listener.progress("Gravando nuvem de pontos", accepted.size(), accepted.size());
        if (cloudBuilder != null) {
            List<PlyWriter.FramePoints> cloud = cloudBuilder.finish();
            PlyWriter.write(cfg.output().resolve("cloud.ply"), cloud, cloudBuilder.unitComment());
            double[] off = cloudBuilder.objectOffset();
            listener.log(String.format(Locale.ROOT,
                    "Nuvem: cobre %.0f° em volta do eixo (cada lâmina vale para o plano dela e o oposto); "
                            + "centro do objeto a %.1f px de referência do eixo, recentrado.",
                    cloudBuilder.coverageDegrees(), Math.hypot(off[0], off[1])));
        } else {
            PlyWriter.write(cfg.output().resolve("cloud.ply"), List.of());
        }
        return new Summary(images.size(), used4, used3, skipped);
    }

    /** Quadro com pose mais próximo (em índice) de {@code f}, a até {@code maxGap} quadros e com no máximo {@code maxHops} saltos. */
    private static Frame nearestWithPose(List<Frame> frames, Frame f, int maxGap, int maxHops) {
        Frame best = null;
        for (Frame c : frames) {
            if (c.pose == null || c.hops > maxHops) continue;
            int gap = Math.abs(c.index - f.index);
            if (gap > maxGap) continue;
            if (best == null || gap < Math.abs(best.index - f.index)
                    || (gap == Math.abs(best.index - f.index) && c.hops < best.hops)) {
                best = c;
            }
        }
        return best;
    }

    private static String counts(Frame f) {
        return String.format("%d triângulo(s) e %d quadrado(s)", f.detection.triangles().size(),
                f.detection.squares().size());
    }

    private static void skip(List<String> skipped, ProgressListener listener, String message) {
        skipped.add(message);
        listener.log("Ignorado: " + message);
    }

    private CameraMatrix loadCamera(Size size, ProgressListener listener) throws IOException {
        CameraMatrix camera;
        if (cfg.approxCamera()) {
            camera = CameraMatrix.approximate(size);
            listener.log(String.format(Locale.ROOT,
                    "Câmera aproximada (sem calibração): f = %.0f px, centro (%.1f, %.1f)", camera.fx(), camera.cx(),
                    camera.cy()));
        } else {
            camera = CameraMatrix.load(cfg.cameraFile()).forImage(size);
            String warning = camera.check(size);
            if (warning != null) listener.log("AVISO: " + warning);
        }
        return camera;
    }

    private static Mat annotate(Mat bgr, Frame f, PoseEstimator estimator) {
        Mat img = bgr.clone();
        boolean low = f.pose.lowConfidence();
        Scalar frameColor = low ? new Scalar(0, 165, 255) : new Scalar(0, 255, 0); // laranja = confiança menor
        String[] labels = {"T0", "S0", "T1", "S1"};
        for (int i = 0; i < 4; i++) {
            if (f.used[i] == null) continue;
            Imgproc.circle(img, f.used[i], 4, new Scalar(0, 0, 255), -1);
            Imgproc.putText(img, labels[i], new Point(f.used[i].x + 6, f.used[i].y - 6), Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.5, new Scalar(255, 0, 0), 1);
        }
        // moldura do modelo reprojetada: tri0 → sq0 → sq1 → tri1
        Point[] frame = estimator.project(List.of(PoseEstimator.MODEL.get(0), PoseEstimator.MODEL.get(1),
                PoseEstimator.MODEL.get(3), PoseEstimator.MODEL.get(2)), f.pose);
        for (int i = 0; i < 4; i++) {
            Imgproc.line(img, frame[i], frame[(i + 1) % 4], frameColor, 2);
        }
        // eixos de 50 mm na origem (tri0): X vermelho, Y verde, Z azul (para cima da folha = -Z)
        Point[] axes = estimator.project(List.of(new Point3(0, 0, 0), new Point3(50, 0, 0), new Point3(0, 50, 0),
                new Point3(0, 0, -50)), f.pose);
        Imgproc.line(img, axes[0], axes[1], new Scalar(0, 0, 255), 2);
        Imgproc.line(img, axes[0], axes[2], new Scalar(0, 255, 0), 2);
        Imgproc.line(img, axes[0], axes[3], new Scalar(255, 0, 0), 2);
        if (low) {
            Imgproc.putText(img, "3 marcadores (confianca menor)", new Point(10, 24), Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.6, frameColor, 2);
        }
        return img;
    }

    /** Nome de arquivo seguro para CSV (aspas se tiver vírgula ou aspas). */
    private static String csv(String v) {
        return v.contains(",") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static boolean isImage(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return !n.startsWith(".") && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"));
    }
}
