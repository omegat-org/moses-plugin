package org.omegat.connectors.machinetranslators.moses;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.omegat.core.Core;
import org.omegat.core.data.NotLoadedProject;
import org.omegat.tokenizer.DefaultTokenizer;
import org.omegat.tokenizer.ITokenizer;
import org.omegat.util.Language;
import org.omegat.util.Preferences;
import org.omegat.util.PreferencesImpl;
import org.omegat.util.PreferencesXML;
import org.omegat.util.RuntimePreferences;

import java.io.File;
import java.nio.file.Files;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@WireMockTest
public class TestMosesTranslate {

    private static final String RPC_PATH = "/RPC2";

    private File tmpDir;

    @BeforeEach
    public final void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("omegat").toFile();
        Assertions.assertTrue(tmpDir.isDirectory());
    }

    @AfterEach
    public final void tearDown() throws Exception {
        FileUtils.deleteDirectory(tmpDir);
    }

    @Test
    void testResponse(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        File prefsFile = new File(tmpDir, Preferences.FILE_PREFERENCES);
        Preferences.IPreferences prefs = new PreferencesImpl(new PreferencesXML(null, prefsFile));
        prefs.setPreference(MosesTranslate.ALLOW_MOSES_TRANSLATE, true);
        init(prefsFile.getAbsolutePath());

        String text = "Buy tomorrow";
        String translation = "Morgen kaufen gehen ein";

        WireMock wireMock = wireMockRuntimeInfo.getWireMock();
        int port = wireMockRuntimeInfo.getHttpPort();
        System.setProperty(MosesTranslate.PROPERTY_MOSES_URL,
                String.format("http://localhost:%d%s", port, RPC_PATH));
        wireMock.register(post(urlPathEqualTo(RPC_PATH)).withHeader("Content-Type", equalTo("text/xml"))
                .withRequestBody(matchingXPath("//methodCall/methodName",
                        equalToXml("<methodName>translate</methodName>")))
                .withRequestBody(matchingXPath("//methodCall/params/param/value/struct/member/name",
                        equalToXml("<name>text</name>")))
                /*
                 * <?xml version=\"1.0\" encoding=\"UTF-8\"?>
                 * <methodCall><methodName>translate</methodName>
                 * <params><param><value><struct> <member> <name>text</name>
                 * <value><string>source text</string></value> </member>
                 * </struct></value></param></params> </methodCall>
                 */
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/xml").withBody(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><methodResponse><params><param><value><struct>"
                                + "<member><name>text</name><value><string>" + translation
                                + "</string></value></member>"
                                + "</struct></value></param></params></methodResponse>")));

        MosesTranslate mosesTranslate = new MosesTranslate();
        String response = mosesTranslate.translate(new Language("EN"), new Language("DE"), text);
        Assertions.assertEquals(translation, response);
    }

    public static synchronized void init(String configDir) {
        RuntimePreferences.setConfigDir(configDir);
        Preferences.init();
        Preferences.initFilters();
        Preferences.initSegmentation();
        Core.setProject(new TestProject());
    }

    static class TestProject extends NotLoadedProject {
        @Override
        public ITokenizer getSourceTokenizer() {
            return new DefaultTokenizer();
        }

    }
}
