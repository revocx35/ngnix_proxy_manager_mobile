import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the store icon (512x512 PNG) from the same geometry as the adaptive launcher icon
 * (app/src/main/res/drawable/ic_launcher_foreground.xml, 108x108 viewport, brand background).
 * Usage: java scripts/RenderIcon.java fastlane/metadata/android/en-US/images/icon.png
 */
public class RenderIcon {
    public static void main(String[] args) throws Exception {
        int size = 512;
        // Launcher icons show the middle 72x72 of the 108x108 canvas; scale that part to the full image.
        double s = size / 72.0, o = -18.0;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new Color(0xF1, 0x58, 0x33));
        g.fillOval(0, 0, size, size);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (4.5 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double[][] hex = {{54, 30}, {74.8, 42}, {74.8, 66}, {54, 78}, {33.2, 66}, {33.2, 42}};
        Path2D p = new Path2D.Double();
        for (int i = 0; i < hex.length; i++) {
            double x = (hex[i][0] + o) * s, y = (hex[i][1] + o) * s;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        g.draw(p);
        Path2D a = new Path2D.Double();
        a.moveTo((43 + o) * s, (54 + o) * s);
        a.lineTo((64 + o) * s, (54 + o) * s);
        a.moveTo((57.5 + o) * s, (47.5 + o) * s);
        a.lineTo((64 + o) * s, (54 + o) * s);
        a.lineTo((57.5 + o) * s, (60.5 + o) * s);
        g.draw(a);
        g.dispose();
        ImageIO.write(img, "png", new File(args[0]));
    }
}
