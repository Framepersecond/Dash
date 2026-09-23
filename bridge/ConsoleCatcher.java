package dash.bridge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class ConsoleCatcher extends AbstractAppender {

    private static final String APPENDER_NAME = "DashBridgeConsole";
    private static final int MAX_LOGS = 200;
    private static final Object LOCK = new Object();
    private static final Deque<String> BUFFER = new ArrayDeque<>();

    public ConsoleCatcher() {
        super(APPENDER_NAME, null, null, false, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        String line = "[" + event.getLevel().name() + "] " + event.getMessage().getFormattedMessage();
        synchronized (LOCK) {
            BUFFER.addLast(line);
            while (BUFFER.size() > MAX_LOGS) {
                BUFFER.removeFirst();
            }
        }
    }

    public static void register() {
        Logger rootLogger = (Logger) LogManager.getRootLogger();
        if (rootLogger.getAppenders().containsKey(APPENDER_NAME)) {
            return;
        }
        ConsoleCatcher appender = new ConsoleCatcher();
        appender.start();
        rootLogger.addAppender(appender);
    }

    public static List<String> getRecentLogs() {
        synchronized (LOCK) {
            return new ArrayList<>(BUFFER);
        }
    }
}

