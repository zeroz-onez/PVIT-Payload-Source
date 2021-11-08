package com.example.arduinoserial;

import org.testng.annotations.Test;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        String message = "stream(0-1_2.png)";
        int start = message.indexOf("(") + 1;
        int middle = message.indexOf(",");
        int end = message.indexOf(")");

        boolean sendNext = false;
        if (message.charAt(end - 1) == '+') {
            end--;
            sendNext = true;
        }

        if (start == 0 || middle < start || end < middle) {

            return;
        }

        String pathWithChunk = message.substring(start, middle);
        String path;
        int underscore = pathWithChunk.lastIndexOf("_");
        int dot = pathWithChunk.lastIndexOf(".");
        int dash = pathWithChunk.indexOf("-");
        int chunk;
        int size = 0;
        if (dot < underscore) {

            return;
        }
        if (dash != -1) {
            try {
                int end_ = underscore != -1 ? underscore : dot;
                size = Integer.parseInt(pathWithChunk.substring(dash+1, end_));
                pathWithChunk = pathWithChunk.substring(0, dash+1) + pathWithChunk.substring(end_);
            }
            catch (NumberFormatException e) {

                return;
            }
        }
        if (underscore == -1) {
            chunk = -1;
            path = pathWithChunk;
        }
        else {
            path = pathWithChunk.substring(0, underscore) + ".png";
            try {
                chunk = Integer.parseInt(pathWithChunk.substring(underscore + 1, dot));
            }
            catch (NumberFormatException e) {

                return;
            }
        }

        System.out.printf("%d %d %s", chunk, size, path);
    }
}