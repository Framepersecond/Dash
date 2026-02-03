package dash;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.LinkedList;
import java.util.List;

public class ConsoleLogAppender extends AbstractAppender {

    private static final int MAX_LOGS = 100;
    private static final LinkedList<String> logBuffer = new LinkedList<>();

    public ConsoleLogAppender() {
        super("DashWebConsole", null, null, false, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        String message = event.getMessage().getFormattedMessage();
        synchronized (logBuffer) {
            logBuffer.add(message);
            if (logBuffer.size() > MAX_LOGS) {
                logBuffer.removeFirst();
            }
        }
    }

    public static List<String> getLogs() {
        synchronized (logBuffer) {
            return new LinkedList<>(logBuffer);
        }
    }

    public static void register() {
        Logger rootLogger = (Logger) LogManager.getRootLogger();
        ConsoleLogAppender appender = new ConsoleLogAppender();
        appender.start();
        rootLogger.addAppender(appender);
    }
}
