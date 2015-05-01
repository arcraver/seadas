package gov.nasa.gsfc.seadas.dataio;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.beam.framework.dataio.ProductIOException;
import org.esa.beam.framework.datamodel.*;
import ucar.ma2.Array;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.Variable;

import java.io.IOException;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: seadas
 * Date: 11/14/11
 * Time: 2:23 PM
  */
public class L2FileReader extends SeadasFileReader {

    L2FileReader(SeadasProductReader productReader) {
        super(productReader);
    }

    @Override
    public Product createProduct() throws ProductIOException {

        int sceneHeight = 0;
        int sceneWidth = 0;

        List<Dimension> dims = ncFile.getDimensions();
        for (Dimension d: dims){
            if ((d.getShortName().equalsIgnoreCase("number_of_lines"))
            ||(d.getShortName().equalsIgnoreCase("Number_of_Scan_Lines")))
                    {
                sceneHeight = d.getLength();
            }
            if ((d.getShortName().equalsIgnoreCase("pixels_per_line"))
            || (d.getShortName().equalsIgnoreCase("Pixels_per_Scan_Line"))){
                sceneWidth = d.getLength();
            }
        }
        if (sceneWidth == 0){
            sceneWidth = getIntAttribute("Pixels_per_Scan_Line");
        }
        if (sceneHeight == 0){
            sceneHeight = getIntAttribute("Number_of_Scan_Lines");
        }
        try {
            String navGroup = "Navigation_Data";
            final String latitude = "latitude";
            if (ncFile.findGroup(navGroup) == null) {
                if (ncFile.findGroup("Navigation") != null) {
                    navGroup = "Navigation";
                }
            }
            if (ncFile.findGroup(navGroup) == null) {
                if (ncFile.findGroup("navigation_data") != null) {
                    navGroup = "navigation_data";
                }
            }
            final Variable variable = ncFile.findVariable(navGroup + "/" + latitude);
            invalidateLines(LAT_SKIP_BAD_NAV,variable);

            sceneHeight -= leadLineSkip;
            sceneHeight -= tailLineSkip;

        } catch (IOException ignore) {

        }
        String productName = getStringAttribute("Product_Name");

        mustFlipX = mustFlipY = getDefaultFlip();
        SeadasProductReader.ProductType productType = productReader.getProductType();
        if (productType == SeadasProductReader.ProductType.Level1A_CZCS ||
                productType == SeadasProductReader.ProductType.Level2_CZCS)
            mustFlipX = false;

        Product product = new Product(productName, productType.toString(), sceneWidth, sceneHeight);
        product.setDescription(productName);

        Attribute startTime = findAttribute("time_coverage_start");
        ProductData.UTC utcStart = getUTCAttribute("time_coverage_start");
        ProductData.UTC utcEnd = getUTCAttribute("time_coverage_end");
        if (startTime == null) {
            utcStart = getUTCAttribute("Start_Time");
            utcEnd = getUTCAttribute("End_Time");
        }

        if (utcStart != null) {
            if (mustFlipY){
                product.setEndTime(utcStart);
            } else {
                product.setStartTime(utcStart);
            }
        }

        if (utcEnd != null) {
            if (mustFlipY) {
                product.setStartTime(utcEnd);
            } else {
                product.setEndTime(utcEnd);
            }
        }

        product.setFileLocation(productReader.getInputFile());
        product.setProductReader(productReader);

        addGlobalMetadata(product);
        addInputParamMetadata(product);
        addBandMetadata(product);
        addScientificMetadata(product);

        variableMap = addBands(product, ncFile.getVariables());

        addGeocoding(product);

        addFlagsAndMasks(product);
        product.setAutoGrouping("Rrs:nLw:Lt:La:Lr:Lw:L_q:L_u:Es:TLg:rhom:rhos:rhot:Taua:Kd:aot:adg:aph_:bbp:vgain:BT:tg_sol:tg_sen");

        return product;
    }

    public void addGeocoding(final Product product) throws ProductIOException {
        // see if bowtie geocoding is needed
        String res = null;
        String sensor = null;
        try {
            sensor = product.getMetadataRoot().getElement("Global_Attributes").getAttribute("instrument").getData().getElemString();
        } catch (Exception ignore) {
            try{
                sensor = product.getMetadataRoot().getElement("Global_Attributes").getAttribute("Sensor_Name").getData().getElemString();
            } catch(Exception ignored) {}
        }
        try {
            res = product.getMetadataRoot().getElement("Global_Attributes").getAttribute("spatialResolution").getData().getElemString();
        } catch (Exception ignore) {
            try{
                res = product.getMetadataRoot().getElement("Input_Parameters").getAttribute("RESOLUTION").getData().getElemString();
            } catch(Exception ignored) {}
        }

        if(sensor != null) {
            sensor = sensor.toLowerCase();
            if(sensor.contains("viirs")) {
                addBowtieGeocoding(product, 16);
                return;
            } else if(sensor.contains("modis")) {
                int scanHeight = 10;
                if(res != null) {
                    if(res.equals("500 m") || res.equals("500")) {
                        scanHeight = 20;
                    } else if(res.equals("250 m") || res.equals("250")) {
                        scanHeight = 40;
                    }
                }
                addBowtieGeocoding(product, scanHeight);
                return;
            } // modis
        }
        addPixelGeocoding(product);
    }

    public void addBowtieGeocoding(final Product product, int scanHeight) throws ProductIOException {
        final String longitude = "longitude";
        final String latitude = "latitude";

        if (product.containsBand(latitude) && product.containsBand(longitude)) {
            Band latBand = product.getBand(latitude);
            Band lonBand = product.getBand(longitude);
            latBand.setNoDataValue(-999.);
            lonBand.setNoDataValue(-999.);
            latBand.setNoDataValueUsed(true);
            lonBand.setNoDataValueUsed(true);
            product.setGeoCoding(new BowtiePixelGeoCoding(latBand, lonBand, scanHeight));
        } else {
            String navGroup = "navigation_data";
            final String cntlPoints = "cntl_pt_cols";
            int subSampleY;
            int subSampleX;
            if (ncFile.findGroup(navGroup) == null) {
                if (ncFile.findGroup("Navigation_Data") != null) {
                    navGroup = "Navigation_Data";
                }
            }
            int scanCntlPts;
            int pixelCntlPts;

            try {
                scanCntlPts = getIntAttribute("scan_control_points");
                pixelCntlPts = getIntAttribute("pixel_control_points");
            } catch (ProductIOException e) {
                scanCntlPts = getIntAttribute("Number_of_Scan_Control_Points");
                pixelCntlPts = getIntAttribute("Number_of_Pixel_Control_Points");
            }

            subSampleY = product.getSceneRasterHeight() / scanCntlPts;
            subSampleX = Math.round((float) product.getSceneRasterWidth() / pixelCntlPts);

            try {
                Variable lats = ncFile.findVariable(navGroup + "/" + latitude);
                Variable lons = ncFile.findVariable(navGroup + "/" + longitude);
                Variable cntlPointVar = ncFile.findVariable(navGroup + "/" + cntlPoints);
                if (lats != null && lons != null && cntlPointVar != null) {

                    int[] dims = lats.getShape();
                    float[] latTiePoints;
                    float[] lonTiePoints;
                    Array latarr = lats.read();
                    Array lonarr = lons.read();

                    if (mustFlipX && mustFlipY) {
                        latTiePoints = (float[]) latarr.flip(0).flip(1).copyTo1DJavaArray();
                        lonTiePoints = (float[]) lonarr.flip(0).flip(1).copyTo1DJavaArray();
                    } else if (!mustFlipX && mustFlipY) {
                            latTiePoints = (float[]) latarr.flip(0).copyTo1DJavaArray();
                            lonTiePoints = (float[]) lonarr.flip(0).copyTo1DJavaArray();
                    } else if (mustFlipX && !mustFlipY) {
                            latTiePoints = (float[]) latarr.flip(1).copyTo1DJavaArray();
                            lonTiePoints = (float[]) lonarr.flip(1).copyTo1DJavaArray();
                    } else {
                        latTiePoints = (float[]) latarr.getStorage();
                        lonTiePoints = (float[]) lonarr.getStorage();
                    }

                    final TiePointGrid latGrid = new TiePointGrid("latitude", dims[1], dims[0], 0, 0,
                            subSampleX, subSampleY, latTiePoints);
                    product.addTiePointGrid(latGrid);

                    final TiePointGrid lonGrid = new TiePointGrid("longitude", dims[1], dims[0], 0, 0,
                            subSampleX, subSampleY, lonTiePoints);
                    product.addTiePointGrid(lonGrid);

                    product.setGeoCoding(new BowtieTiePointGeoCoding(latGrid, lonGrid, scanHeight));

                }
            } catch (IOException e) {
                throw new ProductIOException(e.getMessage());
            }
        }
    }

    public void addPixelGeocoding(final Product product) throws ProductIOException {
        String navGroup = "navigation_data";
        final String longitude = "longitude";
        final String latitude = "latitude";
        final String cntlPoints = "cntl_pt_cols";
        Band latBand = null;
        Band lonBand = null;

        if (product.containsBand(latitude) && product.containsBand(longitude)) {
            latBand = product.getBand(latitude);
            lonBand = product.getBand(longitude);
            latBand.setNoDataValue(-999.);
            lonBand.setNoDataValue(-999.);
            latBand.setNoDataValueUsed(true);
            lonBand.setNoDataValueUsed(true);
        } else {
            if (ncFile.findGroup(navGroup) == null) {
                if (ncFile.findGroup("Navigation_Data") != null) {
                    navGroup = "Navigation_Data";
                } else {
                    if (ncFile.findGroup("Navigation") != null) {
                        navGroup = "Navigation";
                    }
                }
            }
            Variable latVar = ncFile.findVariable(navGroup + "/" + latitude);
            Variable lonVar = ncFile.findVariable(navGroup + "/" + longitude);
            Variable cntlPointVar = ncFile.findVariable(navGroup + "/" + cntlPoints);
            if (latVar != null && lonVar != null && cntlPointVar != null) {
                Array lonRaw;
                Array latRaw;
                float[] latRawData;
                float[] lonRawData;
                try {
                    lonRaw = lonVar.read();
                    latRaw = latVar.read();
                    if (mustFlipX && mustFlipY) {
                        latRawData= (float[]) latRaw.flip(0).flip(1).copyTo1DJavaArray();
                        lonRawData= (float[]) lonRaw.flip(0).flip(1).copyTo1DJavaArray();
                    } else if (!mustFlipX && mustFlipY) {
                        latRawData= (float[]) latRaw.flip(0).copyTo1DJavaArray();
                        lonRawData= (float[]) lonRaw.flip(0).copyTo1DJavaArray();
                    } else if (mustFlipX && !mustFlipY) {
                        latRawData= (float[]) latRaw.flip(1).copyTo1DJavaArray();
                        lonRawData= (float[]) lonRaw.flip(1).copyTo1DJavaArray();
                    } else {
                        latRawData= (float[]) latRaw.copyTo1DJavaArray();
                        lonRawData= (float[]) lonRaw.copyTo1DJavaArray();
                    }
                } catch (IOException e) {
                    throw new ProductIOException(e.getMessage());
                }


                latBand = product.addBand(latVar.getShortName(), ProductData.TYPE_FLOAT32);
                lonBand = product.addBand(lonVar.getShortName(), ProductData.TYPE_FLOAT32);
                latBand.setNoDataValue(-999.);
                lonBand.setNoDataValue(-999.);
                latBand.setNoDataValueUsed(true);
                lonBand.setNoDataValueUsed(true);

                Array cntArray;
                try {
                    cntArray = cntlPointVar.read();
                    int[] colPoints = (int[]) cntArray.getStorage();
                    computeLatLonBandData(product.getSceneRasterHeight(),product.getSceneRasterWidth(),latBand, lonBand,
                            latRawData, lonRawData, colPoints);

                } catch (IOException e) {
                   throw new ProductIOException(e.getMessage());
                }
            }
        }
        if (latBand != null) {
            product.setGeoCoding(new PixelGeoCoding2(latBand, lonBand,null));
        }
    }
}
