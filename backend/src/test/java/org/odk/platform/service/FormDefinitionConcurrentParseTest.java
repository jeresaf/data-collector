package org.odk.platform.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for JavaRosa's process-global non-blocking XFormParser lock.
 *
 * WebFormView loads progress and field-list data concurrently. Application-level
 * parsing must therefore wait/serialize rather than allowing JavaRosa to throw
 * "Another XForm is being parsed!" from one of the requests.
 */
class FormDefinitionConcurrentParseTest {

    @Test
    void concurrentCreateAndRestore_areSerializedInsteadOfFailing() throws Exception {
        String xform = buildLargeForm(300);
        FormDefinition seed = FormDefinition.createNew(xform, "seed");
        String instanceXml = seed.serializeDraft();

        int workers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FormDefinition>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < workers; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return (index % 2 == 0)
                            ? FormDefinition.createNew(xform, "new-" + index)
                            : FormDefinition.createFromInstance(xform, instanceXml, "restore-" + index);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            for (Future<FormDefinition> future : futures) {
                assertNotNull(future.get(20, TimeUnit.SECONDS));
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static String buildLargeForm(int questionCount) {
        StringBuilder instance = new StringBuilder();
        StringBuilder binds = new StringBuilder();
        StringBuilder body = new StringBuilder();

        for (int i = 0; i < questionCount; i++) {
            instance.append("<q").append(i).append("/>");
            binds.append("<bind nodeset=\"/data/q").append(i).append("\" type=\"string\"/>");
            body.append("<input ref=\"/data/q").append(i).append("\"><label>Question ")
                    .append(i).append("</label></input>");
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<h:html xmlns:h=\"http://www.w3.org/1999/xhtml\" "
                + "xmlns=\"http://www.w3.org/2002/xforms\">"
                + "<h:head><h:title>Concurrent Parse</h:title><model><instance>"
                + "<data id=\"concurrent_parse\">" + instance
                + "<meta><instanceID/></meta></data></instance>"
                + binds
                + "<bind nodeset=\"/data/meta/instanceID\" type=\"string\"/>"
                + "</model></h:head><h:body>"
                + body
                + "</h:body></h:html>";
    }
}
