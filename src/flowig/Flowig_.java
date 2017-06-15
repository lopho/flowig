package flowig;

//############################################################################
// java
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.awt.image.BufferedImage;
import java.awt.Rectangle;

//############################################################################
// imagej
import ij.plugin.PlugIn;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.process.ImageProcessor;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.TextRoi;
import ij.gui.Arrow;
import ij.gui.ImageRoi;
import ij.gui.PolygonRoi;
import ij.io.DirectoryChooser;
import ij.process.ColorProcessor;
import java.awt.Composite;

//############################################################################
// opencv
import org.bytedeco.javacpp.opencv_core.Mat;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.UByteRawIndexer;
import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacpp.opencv_imgproc;
import org.bytedeco.javacpp.opencv_optflow;
import org.bytedeco.javacpp.opencv_video;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;


//############################################################################
public class Flowig_ implements PlugIn {

//############################################################################
// constants
    // color used for bounding boxes
    static final int BOUNDS_COLOR = 0xff_ff_00_ff; // magenta
    // how often should images be downscaled before flow computation
    static final int SCALE_SIZE = 0;
    // saturation scale factor for flow image visualization
    static final int SCALE_COLOR = 1;
    
    static final String ARGUMENT_DIVIDER = ",";
    
    private FlowType flowType = FlowType.DIS;
    private String dataDir;

//############################################################################
// main (for debugging purposes only)
    public static void main(String[] args) {
        Flowig_ flowig = new Flowig_();
        flowig.run(null);
    }
    
//############################################################################
// plugin (run)
    @Override
    public void run(String arg) {
                
        if(!checkArguments(arg)){
            DirectoryChooser dirChooser
                    = new DirectoryChooser("Choose directory with images");
            dataDir = dirChooser.getDirectory();
            if (null == dataDir) {
                return;
            }
        }
        
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<ImagePlus> images = new ArrayList<>();
        File dir = new File(dataDir);
        File[] directoryListing = dir.listFiles();
        if (directoryListing != null) {
            for (File child : directoryListing) {
                if (child.isDirectory()) {
                    continue;
                }
                paths.add(child.getAbsolutePath());
            }
            if (paths.size() < 2) {
                IJ.error("Not enough images: please select a folder with two or more images");
                System.exit(1);
            }
            Collections.sort(paths, String::compareTo);
            for (String s : paths) {
                images.add(new ImagePlus(s));
            }
        } 
        
        Overlay o = getBoundsBasedMovement(images, false);
        ArrayList<ImagePlus> flows = getOpticalFlowBasedMovement(images, false, flowType);
        
        final int w = images.get(0).getWidth();
        final int h = images.get(0).getHeight();
        final Composite comp = BlendComposite.getInstance(BlendComposite.BlendingMode.MULTIPLY);
        
        ImageRoi imgRoi = new ImageRoi(0, 0, flows.get(0).getProcessor());
        imgRoi.setComposite(comp);
        o.add(imgRoi);
        
        ImagePlus vizImg = new ImagePlus("flow", images.get(0).getProcessor());
        vizImg.setImage(images.get(0));
        vizImg.setOverlay(o);
        vizImg.show();
        
        for (int i = 0; i < images.size(); ++i) {
            vizImg.setImage(images.get(i));
            o.remove(imgRoi);
            imgRoi = new ImageRoi(0, 0, flows.get(i).getProcessor());
            imgRoi.setComposite(comp);
            o.add(imgRoi);
            IJ.wait(1000);
        }
    }
    
   
//############################################################################    
//############################################################################    
// bounding box based movement estimation
//############################################################################    
//############################################################################
// ######## draw movement vectors for all images into overlay
    static public Overlay getBoundsBasedMovement(ArrayList<ImagePlus> images, boolean viz) {
        if (images.size() < 2)
            return new Overlay();
        
        ArrayList<Vector> centers = new ArrayList<>();
        ArrayList<Rectangle> bounds = new ArrayList<>();
        
        final int w = images.get(0).getWidth();
        final int h = images.get(0).getHeight();
        
        ImageProcessor vizIp = new ColorProcessor(w, h);
        ImagePlus vizImg = new ImagePlus("movement", vizIp);
        
        Overlay o = new Overlay();
        
        if (viz) {
            vizImg.setOverlay(o);
            vizImg.show();
        }
        
        int i = 0;
        for (ImagePlus img : images) {
            Rectangle bound = getBounds(img, BOUNDS_COLOR);

            if(bound.width == 0 || bound.height == 0) {
                continue;
            }
            
            bounds.add(bound);
                           
            float centerX = bound.x + bound.width / 2f;
            float centerY = bound.y + bound.height / 2f;
           
            centers.add(new Vector(centerX, centerY));
            
            if (i > 0) {
                Vector centerA = centers.get(i-1);
                Vector centerB = centers.get(i);
                Arrow roi = new Arrow(centerA.x, centerA.y,
                                      centerB.x, centerB.y);
                TextRoi idRoi = new TextRoi(centerB.x, centerB.y, ""+i);
                o.add(idRoi);
                System.out.println("" + i + ": ");
                o.add(roi);
                Vector ab = centerB.minus(centerA);
                double length = ab.length();
                System.out.println("Velocity: " + length);
                Vector direction = ab.unit();
                System.out.println("Direction: " + direction);
                System.out.println("--------");
            }  
            i++;
            
            if (viz)
                vizImg.setImage(img);
        }
        
        System.out.println("==========================");
        
        if (viz)
            vizImg.close();
        
        return o;
    }
       
// ######## bbox detection    
    static public Rectangle getBounds(ImagePlus image, int boundsColor) {
        ImageProcessor ip = image.getProcessor();
        final int w = image.getWidth();
        final int h = image.getHeight();
        
        int[][] pix = ip.getIntArray();
        ArrayList<Float> pListX = new ArrayList<>();
        ArrayList<Float> pListY = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (pix[x][y] == boundsColor) {
                    pListX.add((float)x);
                    pListY.add((float)y);
                }
            }
        }
        float[] pX = new float[pListY.size()];
        float[] pY = new float[pListX.size()];
        for (int i = 0; i < pListX.size(); ++i) {
            pX[i] = (float)pListX.get(i);
            pY[i] = (float)pListY.get(i);
        }

        PolygonRoi pointsRoi =
            new PolygonRoi(pX, pY,
                           Roi.POLYGON);
        
        Rectangle bounds = pointsRoi.getBounds();
        return bounds;
    }
    
//############################################################################    
//############################################################################    
// optical flow
//############################################################################    
//############################################################################ 
// ######## compute and vizualize optical flow for all images
    static public ArrayList<ImagePlus> getOpticalFlowBasedMovement(ArrayList<ImagePlus> images, boolean viz, FlowType flowType) {
        ArrayList<ImagePlus> imagesOut = new ArrayList<>();
        
        if (images.size() < 2)
            return imagesOut;
        
        
        
        ImagePlus img0 = images.get(0);
        final int w = img0.getWidth();
        final int h = img0.getHeight();
        
        ImageProcessor vizIp = new ColorProcessor(w, h);
        ImagePlus vizImg = new ImagePlus("flow", vizIp);
        
        if (viz)
            vizImg.show();
        
        for (final ImagePlus img1 : images) {
            Mat mat0 = toMat(img0);
            Mat mat1 = toMat(img1);
            for (int i = 0; i < SCALE_SIZE; ++i) {
                opencv_imgproc.pyrDown(mat0, mat0);
                opencv_imgproc.pyrDown(mat1, mat1);
            }
            Mat flow = makeFlow(mat0, mat1, flowType);
            Mat flowImg = drawOpticalFlow(flow);
            for (int i = 0; i < SCALE_SIZE; ++i) {
                opencv_imgproc.pyrUp(flowImg, flowImg);
            }
            ImagePlus img = toImagePlus(flowImg);
            imagesOut.add(img);
            img0.setImage(img1);
            
            if (viz)
                vizImg.setImage(img);
        }
        
        if (viz)
            vizImg.close();
        
        return imagesOut;
    }

    /**
     * 
     * @return True on correct arguments else false
     */
    private boolean checkArguments(String arg) {
        
        String options = arg != null? arg : Macro.getOptions();
                        
        if(options == null){
            return false;
        }
        
        String [] arguments = options.split(ARGUMENT_DIVIDER);
        
        for (String s : arguments){
            
            String[] values = s.split("=");
            String name = values[0];
            String value = values[1];
            
            switch (name) {
                case "path": 
                    dataDir = value
                            .trim()
                            .replace("~", System.getenv("HOME"));
                    break;
                case "flow": 
                    flowType = FlowType.valueOf(value.trim());
                    break;
            }
        }
        
        IJ.log("dataDir: " + dataDir);
        IJ.log("exists? " +new File(dataDir).exists());
        IJ.log("flowType: " + flowType);
     
        
        return true;
    }
    
// ######## flow type
    static public enum FlowType {
        SparseToDense,
        FarneBack,
        DeepFlow,
        DIS,
        DualTVL1
    }
   
// ######## flow computation
    static public Mat makeFlow(Mat mat0, Mat mat1, FlowType type) {
        opencv_imgproc.cvtColor(mat0, mat0, opencv_imgproc.COLOR_BGR2GRAY);
        opencv_imgproc.equalizeHist(mat0, mat0);
        opencv_imgproc.cvtColor(mat1, mat1, opencv_imgproc.COLOR_BGR2GRAY);
        opencv_imgproc.equalizeHist(mat1, mat1);
        opencv_video.DenseOpticalFlow flow;
        switch (type) {
            default:
            case SparseToDense:
                flow = opencv_optflow.createOptFlow_SparseToDense();
                break;
            case FarneBack:
                flow = opencv_optflow.createOptFlow_Farneback();
                break;
            case DeepFlow:
                flow = opencv_optflow.createOptFlow_DeepFlow();
                break;
            case DIS:
                flow = opencv_optflow.createOptFlow_DIS();
                break;
            case DualTVL1:
                flow = opencv_video.createOptFlow_DualTVL1();
                break;
        }
        Mat matFlow = new Mat();
        flow.calc(mat0, mat1, matFlow);
        return matFlow;
    }
    
// ######## flow vector validation
    static public boolean isFlowCorrect(float ux, float uy) {
        return (   !Float.isNaN(ux)
                && !Float.isNaN(uy)
                && Math.abs(ux) < 1e9
                && Math.abs(uy) < 1e9);
    }

// ######## flow visualization colors (as color wheel)
    static final int RY = 15;
    static final int YG = 6;
    static final int GC = 4;
    static final int CB = 11;
    static final int BM = 13;
    static final int MR = 6;
    static final int NCOLS = RY + YG + GC + CB + BM + MR;
    static final Color[] COLORWHEEL = new Color[NCOLS];
    static {
        int k = 0;
        for (int i = 0; i < RY; ++i, ++k) {
            COLORWHEEL[k] = new Color(255, 255 * i / RY, 0);
        }
        for (int i = 0; i < YG; ++i, ++k) {
            COLORWHEEL[k] = new Color(255 - 255 * i / YG, 255, 0);
        }
        for (int i = 0; i < GC; ++i, ++k) {
            COLORWHEEL[k] = new Color(0, 255, 255 * i / GC);
        }
        for (int i = 0; i < CB; ++i, ++k) {
            COLORWHEEL[k] = new Color(0, 255 - 255 * i / CB, 255);
        }
        for (int i = 0; i < BM; ++i, ++k) {
            COLORWHEEL[k] = new Color(255 * i / BM, 0, 255);
        }
        for (int i = 0; i < MR; ++i, ++k) {
            COLORWHEEL[k] = new Color(255, 0, 255 - 255 * i / MR);
        }
    }
    
// ######## visualize flow vector as color
    static public Color computeColor(final float fx, final float fy) {
        final float rad = (float) (Math.sqrt(fx * fx + fy * fy));
        final float a = (float) (Math.atan2(-fy, -fx) / Math.PI);
        return computeColorRad(rad, a);
    }
    
    static public Color computeColorRad(final float rad, final float a) {
        final float fk = (a + 1.0f) / 2.0f * (NCOLS - 1);
        final int k0 = (int) fk;
        final int k1 = (k0 + 1) % NCOLS;
        final float f = fk - k0;

        Color pix = new Color();

        for (int b = 0; b < 3; b++) {
            final float col0 = COLORWHEEL[k0].get(b) / 255.f;
            final float col1 = COLORWHEEL[k1].get(b) / 255.f;

            float col = (1 - f) * col0 + f * col1;

            if (rad <= 1) {
                col = 1 - rad * (1 - col);  // increase saturation with radius
            } else {
                col *= .75f; // out of range
            }
            pix.set(2 - b, (int) (255.f * col));
        }

        return pix;
    }
    
// ######## visualize entire flow field with colors
    static public Mat drawOpticalFlow(final Mat flow) {
        return drawOpticalFlow(flow, -1);
    }
    static public Mat drawOpticalFlow(final Mat flow, final float maxmotion) {
//        ImageProcessor ip = new ColorProcessor(flow.cols(), flow.rows());
//        ImagePlus imp = new ImagePlus("flow", ip);
        Mat dst = new Mat(flow.size(), opencv_core.CV_8UC3);

        // determine motion range:
        float maxrad = maxmotion;
        FloatIndexer idx = flow.createIndexer();
        if (maxmotion <= 0) {
            maxrad = 1;
            for (int y = 0; y < flow.rows(); ++y) {
                for (int x = 0; x < flow.cols(); ++x) {
                    final float ux = idx.get(y, x * 2);
                    final float uy = idx.get(y, x * 2 + 1);

                    if (!isFlowCorrect(ux, uy)) {
                        continue;
                    }

                    maxrad = (float) Math.max(maxrad, Math.sqrt(ux * ux + uy * uy));
                }
            }
        }

        UByteRawIndexer idxOut = dst.createIndexer(true);
        for (int y = 0; y < flow.rows(); ++y) {
            for (int x = 0; x < flow.cols(); ++x) {
                final float ux = idx.get(y, x * 2);
                final float uy = idx.get(y, x * 2 + 1);

                if (!isFlowCorrect(ux, uy)) {
                    continue;
                }
                final Color c = computeColor(ux / maxrad, uy / maxrad);
//                ip.set(x, y, (c.r * m) << 16 | (c.g * m) << 8 | (c.b * m));
                idxOut.put(y, x * 3, c.r*SCALE_COLOR);
                idxOut.put(y, x * 3 + 1, c.g*SCALE_COLOR);
                idxOut.put(y, x * 3 + 2, c.b*SCALE_COLOR);
            }
        }
        return dst;
    }

//############################################################################    
//############################################################################
// Vector
//############################################################################
//############################################################################
    static public class Vector {
        double x = 0;
        double y = 0;
        
        public Vector(final double x,final double y) {
            this.x = x;
            this.y = y;
        }
        
       public Vector minus(final Vector other) {
            final double x = this.x - other.x;
            final double y = this.y - other.y;
            return new Vector(x, y);
       }
       public Vector divide(final double d) {
            return new Vector(x/d, y/d);
        }
       public double length() {
           return Math.sqrt(Math.pow(x, 2) + Math.pow(y, 2));
       }
       public Vector unit() {
            return divide(length());
       }
       @Override
       public String toString() {
            return "(" + x + "," + y + ")";
       }
       
    }
    
//############################################################################
//############################################################################    
// Color
//############################################################################
//############################################################################
    static public class Color {

        public int r = 0;
        public int g = 0;
        public int b = 0;

        public Color(int r, int g, int b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }

        public Color() {
            this(0, 0, 0);
        }

        public void set(int i, int c) {
            switch (i) {
                case 0:
                    r = c;
                    break;
                case 1:
                    g = c;
                    break;
                case 2:
                    b = c;
                    break;
                default:
                    break;
            }
        }

        public int get(int i) {
            switch (i) {
                case 0:
                    return r;
                case 1:
                    return g;
                case 2:
                    return b;
                default:
                    return 0;
            }
        }
    }
    
//############################################################################
//############################################################################
// opencv <-> imagej
//############################################################################
//############################################################################
    static public ImagePlus toImagePlus(final Mat mat, final String title) {
        Java2DFrameConverter javaConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
        final Frame frame = matConverter.convert(mat);
        final BufferedImage buffImage = javaConverter.convert(frame);
        return new ImagePlus(title, buffImage);
    }
    
    static public ImagePlus toImagePlus(final Mat mat) {
        return toImagePlus(mat, "");
    }
    
    static public Mat toMat(final ImagePlus imagePlus) {
        Java2DFrameConverter javaConverter = new Java2DFrameConverter();
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
        final BufferedImage image = imagePlus.getBufferedImage();        
        final Frame frame = javaConverter.convert(image);
        return matConverter.convert(frame);
    }

//############################################################################
//############################################################################

}


