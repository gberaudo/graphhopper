/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.reader.dem;

import com.graphhopper.util.Downloader;
import com.graphhopper.util.Helper;
import org.apache.commons.io.FileUtils;
import org.apache.xmlgraphics.image.codec.util.SeekableStream;
import org.locationtech.proj4j.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.Raster;
import java.io.*;
import java.util.*;


/**
 * Elevation data from Swissalti3d, Swisstopo, opendata available from
 * https://www.swisstopo.admin.ch/en/geodata/height/alti3d.html
 * Select "entire dataset" then retrieve the CSV file with the list of URLs.
 * Every file covers a region of 1000x1000 meters (2m step).
 * A tile size is 500x500 pixels.
 *
 * @author Guillaume Beraudo
 */
public class Swissalti3dElevationProvider implements ElevationProvider {
    // X and Y ranges of acceptable values
    private final int[][] ranges = {{2_420_000, 2_900_000}, {1_000_000, 1_350_000}};

    final private Logger logger = LoggerFactory.getLogger(getClass());

    private static final String tilingSchemeUrl = "https://ogd.swisstopo.admin.ch/services/swiseld/services/assets/ch.swisstopo.swissalti3d/search?format=image/tiff;%20application=geotiff;%20profile=cloud-optimized&resolution=2.0&srid=2056&state=current&csv=true";

    private int band = 0; // the tif band containing the elevation data

    private File cacheDir;

    private Map<String, String> tileUrlByXY;

    private final Map<String, Raster> rasterByXY = new HashMap<>();

    void setDownloader(Downloader downloader) {
        this.downloader = downloader;
    }

    private Downloader downloader;

    private boolean autoremoveTemporary = false;

    @Override
    public boolean canInterpolate() {
        return false;
    }

    @Override
    public void release() {
        if (autoremoveTemporary) {
            cacheDir.delete();
        }
    }


    public Swissalti3dElevationProvider(String cacheDirString) {
        File cacheDir = new File(cacheDirString.isEmpty() ? "/tmp/swissalti3d" : cacheDirString);
        if (cacheDir.exists() && !cacheDir.isDirectory())
            throw new IllegalArgumentException("Cache path has to be a directory");
        try {
            this.cacheDir = cacheDir.getCanonicalFile();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        this.downloader = new Downloader("GraphHopper Swissalti3dReader").setTimeout(10000);
    }

    public Swissalti3dElevationProvider() {
        this("");
    }

    /**
     * Create a transform for projecting from EPSG:4326 to EPSG:2056 (Swiss projections)
     * @return the proj4j transform
     */
    CoordinateTransform getCoordinatesTransform() {
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem WGS84 = crsFactory.createFromName("epsg:4326");
        CoordinateReferenceSystem epsg2056 = crsFactory.createFromName("epsg:2056");
        CoordinateTransformFactory ctFactory = new CoordinateTransformFactory();
        return ctFactory.createTransform(WGS84, epsg2056);
    }

    /**
     * Fetch some remote files and create a mapping from (x,y) to tile URLs. This is necessary because the URL naming
     * also depends on the year the file data has been captured. See the technical specification of the dataset for details.
     * @param downloader
     * @return the URL mapping
     * @throws IOException remote file retrieving errors
     */
    private Map<String, String> fetchTileMapping(Downloader downloader) throws IOException {
        File cachedMappingFile = new File(cacheDir, "mappings.csv");
        if (!cachedMappingFile.exists()) {
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            String response = downloader.downloadAsString(Swissalti3dElevationProvider.tilingSchemeUrl, false);
            // ex: {"href":"https://ogd.swisstopo.admin.ch/resources/ch.swisstopo.swissalti3d-9u0iezRG.csv"}
            String csvUrl = response.split("\"")[3];
            downloader.downloadFile(csvUrl, cachedMappingFile.getAbsolutePath());
        }

        String csv = FileUtils.readFileToString(cachedMappingFile, "ascii");
        Scanner scanner = new Scanner(csv);
        Map<String, String> mapping = new HashMap<>();
        while (scanner.hasNextLine()) {
            // ex: https://data.geo.admin.ch/ch.swisstopo.swissalti3d/swissalti3d_2019_2501-1120/swissalti3d_2019_2501-1120_2_2056_5728.tif
            String line = scanner.nextLine();
            int idx = line.lastIndexOf("/");
            if (idx >= 0) {
                String part = line.substring(idx);
                String[] split = part.split("_");
                String xy = split[2];
                mapping.put(xy, line);
            }
        }
        return mapping;
    }

    private String xyToTileKey(int x, int y) {
        return String.format("%s-%s", x / 1000, y / 1000);
    }

    File getCachedTileFile(String xy, String tileUrl) {
        int idx = tileUrl.lastIndexOf("/");
        String filename = tileUrl.substring(idx);
        File cachedFile = new File(cacheDir, filename);
        return cachedFile;
    }

    private Raster getRasterContainingCoordinate(int x, int y) throws  IOException {
        // Best case: return it from the cache
        String xy = xyToTileKey(x, y);

        Raster raster = rasterByXY.get(xy);
        if (raster != null) {
            return raster;
        }

        // lazy load the map of tile URLs
        if (tileUrlByXY == null) {
            tileUrlByXY = this.fetchTileMapping(downloader);
        }

        // Retrieve remote tile if not existing locally
        String tileUrl = tileUrlByXY.get(xy);
        File cachedFile = getCachedTileFile(xy, tileUrl);
        if (!cachedFile.exists()) {
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            downloader.downloadFile(tileUrl, cachedFile.getAbsolutePath());
        }

        // Parse tif file as raster and return it
        raster = readTifFile(cachedFile);
        if (rasterByXY.size() > 100) {
            logger.debug("Clearing in memory raster cache");
            rasterByXY.clear();
        }
        rasterByXY.put(xy, raster);
        return raster;
    }


    @Override
    public double getEle(double lat, double lon) {
        CoordinateTransform transform = getCoordinatesTransform();
        ProjCoordinate result = new ProjCoordinate();
        transform.transform(new ProjCoordinate(lon, lat), result);
        int x = (int) result.x;
        int y = (int) result.y;
        if (isInsideSupportedArea(x, y)) {
            try {
                Raster raster = getRasterContainingCoordinate(x, y);
                // a tile is 500px for 1000m
                // it is oriented North to South so we need to invert y
                float ele = raster.getSampleFloat(Math.floorMod(x, 1000) / 2, 499 - Math.floorMod(y, 1000) / 2, band);
                return ele;
            } catch (IOException e) {
                logger.warn("Could not get raster data for (%d, %d)", x, y);
            }
        }
        return 0;
    }


    Raster readTifFile(File tifFile) {
        SeekableStream ss = null;
        try {
            InputStream is = new FileInputStream(tifFile);
            Raster raster = ImageIO.read(is).getData();
            return raster;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Can't decode " + tifFile.getName(), e);
        } finally {
            if (ss != null)
                Helper.close(ss);
        }
    }


    boolean isInsideSupportedArea(int x, int y) {
        return x <= ranges[0][1] && x >= ranges[0][0] && y <= ranges[1][1] && y >= ranges[1][0];
    }


    @Override
    public String toString() {
        return "swissalti3d";
    }


    public static void main(String[] args) {
        Swissalti3dElevationProvider provider = new Swissalti3dElevationProvider();
        // Dent de Morcles 2571970 1116492 2969m
        // echo "2571970 1116492" | gdaltransform -t_srs EPSG:4326 -s_srs EPSG:2056
        // 7.07552341505788 46.1992990818056 0
        System.out.println(provider.getEle(46.1992990818056, 7.07552341505788));
    }

    public Swissalti3dElevationProvider setAutoRemoveTemporaryFiles(boolean removeTempElevationFiles) {
        autoremoveTemporary = removeTempElevationFiles;
        return this;
    }
}
