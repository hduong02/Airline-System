import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Executes tests against production classes from the deployed image. */
public class RecoveryTestRunner {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request();
        for (String name : args) request.selectors(DiscoverySelectors.selectClass(name));
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request.build());
        var summary = listener.getSummary();
        var output = new PrintWriter(System.out, true);
        summary.printTo(output);
        summary.printFailuresTo(output);
        System.out.printf("RECOVERY_RESULT tests=%d passed=%d failed=%d skipped=%d%n",
                summary.getTestsFoundCount(), summary.getTestsSucceededCount(),
                summary.getTotalFailureCount(), summary.getTestsSkippedCount());
        if (summary.getTestsFoundCount() == 0 || summary.getTotalFailureCount() != 0
                || summary.getTestsSucceededCount() != summary.getTestsFoundCount()) System.exit(1);
    }
}
