package com.github.skrcode.javaautounittests;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Runs <code>./gradlew test jacocoTestReport -q</code> in the project root and parses
 * <code>build/reports/jacoco/test/jacocoTestReport.xml</code> to compute LINE coverage for the
 * CUT. Works entirely with JaCoCo, so it’s IDE‑agnostic and compatible with IntelliJ Community.
 */
public final class CoverageJacocoUtil {

    private static final int PROCESS_TIMEOUT_SEC = 180;

    public static double runCoverageFor(Project project, PsiClass cut) {
        try {
            String qName = cut.getQualifiedName();
            if (qName == null) return 0.0;

            Path base = Path.of(project.getBasePath());
            // 1 ▸ Run Gradle tests + Jacoco report silently
            ProcessBuilder pb = new ProcessBuilder(
                    base.resolve(System.getProperty("os.name").startsWith("Windows") ? "gradlew.bat" : "gradlew").toString(),
                    "test", "jacocoTestReport", "-q")
                    .directory(base.toFile());
            Process proc = pb.start();
            if (!proc.waitFor(PROCESS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return 0.0;
            }

            // 2 ▸ Parse XML report
            File xml = base.resolve("build/reports/jacoco/test/jacocoTestReport.xml").toFile();
            if (!xml.isFile()) return 0.0;

            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
            doc.getDocumentElement().normalize();
            String internal = qName.replace('.', '/') + ".class";
            NodeList classNodes = doc.getElementsByTagName("class");
            for (int i = 0; i < classNodes.getLength(); i++) {
                Element cls = (Element) classNodes.item(i);
                if (!internal.equals(cls.getAttribute("name"))) continue;
                NodeList counters = cls.getElementsByTagName("counter");
                for (int j = 0; j < counters.getLength(); j++) {
                    Element c = (Element) counters.item(j);
                    if ("LINE".equals(c.getAttribute("type"))) {
                        int missed = Integer.parseInt(c.getAttribute("missed"));
                        int covered = Integer.parseInt(c.getAttribute("covered"));
                        int total = missed + covered;
                        return total == 0 ? 0.0 : covered / (double) total;
                    }
                }
            }
            return 0.0;
        } catch (Exception ex) {
            ex.printStackTrace();
            return 0.0;
        }
    }

    private CoverageJacocoUtil() {}
}