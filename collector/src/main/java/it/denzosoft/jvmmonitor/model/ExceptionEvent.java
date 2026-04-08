package it.denzosoft.jvmmonitor.model;

public final class ExceptionEvent {

    private final long timestamp;
    private final int totalThrown;
    private final int totalCaught;
    private final int totalDropped;
    private final String exceptionClass;
    private final String message;        /* exception.getMessage() */
    private final String causeClass;     /* exception.getCause().getClass().getName() */
    private final String causeMessage;   /* exception.getCause().getMessage() */
    private final String throwClass;
    private final String throwMethod;
    private final long throwLocation;
    private final boolean caught;
    private final String catchClass;
    private final String catchMethod;
    private final StackFrame[] stackFrames;

    public ExceptionEvent(long timestamp, int totalThrown, int totalCaught, int totalDropped,
                          String exceptionClass, String throwClass, String throwMethod,
                          long throwLocation, boolean caught, String catchClass, String catchMethod) {
        this(timestamp, totalThrown, totalCaught, totalDropped, exceptionClass,
             throwClass, throwMethod, throwLocation, caught, catchClass, catchMethod, null);
    }

    public ExceptionEvent(long timestamp, int totalThrown, int totalCaught, int totalDropped,
                          String exceptionClass, String throwClass, String throwMethod,
                          long throwLocation, boolean caught, String catchClass, String catchMethod,
                          StackFrame[] stackFrames) {
        this(timestamp, totalThrown, totalCaught, totalDropped, exceptionClass,
             null, null, null,
             throwClass, throwMethod, throwLocation, caught, catchClass, catchMethod, stackFrames);
    }

    public ExceptionEvent(long timestamp, int totalThrown, int totalCaught, int totalDropped,
                          String exceptionClass, String message, String causeClass, String causeMessage,
                          String throwClass, String throwMethod,
                          long throwLocation, boolean caught, String catchClass, String catchMethod,
                          StackFrame[] stackFrames) {
        this.timestamp = timestamp;
        this.totalThrown = totalThrown;
        this.totalCaught = totalCaught;
        this.totalDropped = totalDropped;
        this.exceptionClass = exceptionClass;
        this.message = message;
        this.causeClass = causeClass;
        this.causeMessage = causeMessage;
        this.throwClass = throwClass;
        this.throwMethod = throwMethod;
        this.throwLocation = throwLocation;
        this.caught = caught;
        this.catchClass = catchClass;
        this.catchMethod = catchMethod;
        this.stackFrames = stackFrames;
    }

    public long getTimestamp() { return timestamp; }
    public int getTotalThrown() { return totalThrown; }
    public int getTotalCaught() { return totalCaught; }
    public int getTotalDropped() { return totalDropped; }
    public String getExceptionClass() { return exceptionClass; }
    public String getMessage() { return message; }
    public String getCauseClass() { return causeClass; }
    public String getCauseMessage() { return causeMessage; }
    public String getThrowClass() { return throwClass; }
    public String getThrowMethod() { return throwMethod; }
    public long getThrowLocation() { return throwLocation; }
    public boolean isCaught() { return caught; }
    public String getCatchClass() { return catchClass; }
    public String getCatchMethod() { return catchMethod; }

    public StackFrame[] getStackFrames() { return stackFrames; }
    public int getStackDepth() { return stackFrames != null ? stackFrames.length : 0; }

    public String getStackTraceString() {
        if (stackFrames == null || stackFrames.length == 0) return "(no stack trace)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stackFrames.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append("    at ").append(stackFrames[i].getDisplayName());
        }
        return sb.toString();
    }

    public String getDisplayName() {
        String name = exceptionClass;
        if (name != null && name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1).replace('/', '.');
        }
        return name != null ? name : "Unknown";
    }

    /** Full display: ClassName: message [caused by CauseClass: causeMessage] */
    public String getFullDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(getDisplayName());
        if (message != null && message.length() > 0) {
            sb.append(": ").append(message);
        }
        if (causeClass != null && causeClass.length() > 0) {
            sb.append(" [caused by ").append(causeClass);
            if (causeMessage != null && causeMessage.length() > 0) {
                sb.append(": ").append(causeMessage);
            }
            sb.append("]");
        }
        return sb.toString();
    }

    public static final class StackFrame {
        private final String className;
        private final String methodName;
        private final int lineNumber;

        public StackFrame(String className, String methodName, int lineNumber) {
            this.className = className;
            this.methodName = methodName;
            this.lineNumber = lineNumber;
        }

        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
        public int getLineNumber() { return lineNumber; }

        public String getDisplayName() {
            String cls = className != null ? className : "?";
            String meth = methodName != null ? methodName : "?";
            return cls + "." + meth + "():" + lineNumber;
        }
    }
}
