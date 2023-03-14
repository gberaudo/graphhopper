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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Guillaume Beraudo
 */
public class Swissalti3dProviderTest {

    Swissalti3dElevationProvider instance;

    @BeforeEach
    public void setUp() {
        instance = new Swissalti3dElevationProvider();
    }

    @AfterEach
    public void tearDown() {
        instance.release();
    }

    @Test
    public void testTileFileName() {
        String prefix = "https://data.geo.admin.ch/ch.swisstopo.swissalti3d";
        assertEquals(
                "swissalti3d_2019_2501-1120_2_2056_5728.tif",
                instance.getCachedTileFile(
                        null,
                        prefix + "/swissalti3d_2019_2501-1120/swissalti3d_2019_2501-1120_2_2056_5728.tif").getName()
        );
    }


    @Disabled
    @Test
    public void testGetEle() {
        int delta = 2;
        assertEquals(562, instance.getEle(45.82232, 9.02920), delta);
        assertEquals(2969, instance.getEle(46.1992990818056, 7.07552341505788), delta);

        // Outside of Swissalti3d covered area
        assertEquals(0, instance.getEle(60.0000001, 16), delta);
        assertEquals(0, instance.getEle(60.0000001, 16), delta);
        assertEquals(0, instance.getEle(60.0000001, 19), delta);
        assertEquals(0, instance.getEle(60.251, 18.805), delta);
    }
}
