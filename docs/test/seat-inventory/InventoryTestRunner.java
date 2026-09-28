import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Runs the suite in the deployed image without Maven or an application startup. */
public class InventoryTestRunner {
    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClass(
                "com.example.seat_service.inventory.SeatInventoryIntegrationTest")).build());
        var summary = listener.getSummary();
        var output = new PrintWriter(System.out, true);
        summary.printTo(output);
        summary.printFailuresTo(output);
        System.out.printf("INVENTORY_RESULT tests=%d passed=%d failed=%d skipped=%d%n",
                summary.getTestsFoundCount(), summary.getTestsSucceededCount(),
                summary.getTotalFailureCount(), summary.getTestsSkippedCount());
        if (summary.getTestsSucceededCount() != 9 || summary.getTotalFailureCount() != 0) System.exit(1);
    }
}
