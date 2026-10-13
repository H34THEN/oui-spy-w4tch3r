package com.heathen.persephone;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.time.Instant;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class MapDetectorTest {
    @Test public void annotatedRavenCarriesExportableEvidenceAndPriority() throws Exception {
        JSONObject row=new JSONObject().put("radio","BLE").put("address","AA:BB:CC:00:00:01").put("name","")
            .put("services",new JSONArray().put("0x3100").put("3200"));
        DetectionAnnotator.annotate(row);
        assertTrue(row.getJSONArray("tags").toString().contains("RAVEN"));assertEquals("MEDIUM",row.getString("confidence"));
        assertEquals(4,row.getInt("priority"));assertTrue(row.getString("evidence").contains("unverified"));assertTrue(row.getJSONArray("rule_ids").length()>0);
    }
    @Test public void orderedProbeMetadataCorroboratesButDoesNotConfirmIdentity() throws Exception {
        JSONObject row=new JSONObject().put("address","70:C9:4E:00:00:01").put("wildcard_probe",true).put("ie_fingerprint",true);
        DetectionAnnotator.annotate(row);assertEquals("CORROBORATED",row.getString("confidence"));assertTrue(row.getString("evidence").contains("unverified"));
    }
    @Test public void mockedOrMissingGpsCannotProduceMapPoint() throws Exception {
        JSONObject row=new JSONObject().put("time",Instant.now().toString()).put("rssi",-60);
        assertNull(DetectionAnnotator.point(row));
        row.put("latitude",36).put("longitude",-115).put("accuracy",5).put("gps_mock",true);assertNull(DetectionAnnotator.point(row));
        row.put("gps_mock",false).put("source","DEMO");assertNotNull(DetectionAnnotator.point(row));assertTrue(DetectionAnnotator.point(row).demo);
    }
    @Test public void mapHttpCannotFetchUntilExplicitlyEnabled() {
        TileCache tiles=new TileCache(RuntimeEnvironment.getApplication());assertFalse(tiles.isOnline());assertNull(tiles.get(17,1,1,()->{}));tiles.close();
    }
}
