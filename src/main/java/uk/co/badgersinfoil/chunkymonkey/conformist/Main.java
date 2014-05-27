package uk.co.badgersinfoil.chunkymonkey.conformist;

import io.airlift.command.Arguments;
import io.airlift.command.Command;
import io.airlift.command.HelpOption;
import io.airlift.command.Option;
import io.airlift.command.SingleCommand;
import io.netty.util.ResourceLeakDetector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import org.eclipse.jetty.server.Server;
import uk.co.badgersinfoil.chunkymonkey.ConsoleReporter;
import uk.co.badgersinfoil.chunkymonkey.Reporter;
import uk.co.badgersinfoil.chunkymonkey.conformist.api.ServerBuilder;
import uk.co.badgersinfoil.chunkymonkey.conformist.redundancy.HlsRedundantStreamContext;
import uk.co.badgersinfoil.chunkymonkey.conformist.redundancy.HlsRedundantStreamProcessor;
import uk.co.badgersinfoil.chunkymonkey.hls.HlsMasterPlaylistContext;
import uk.co.badgersinfoil.chunkymonkey.hls.HlsMasterPlaylistProcessor;

@Command(name = "conformist", description = "Media stream checker")
public class Main {

	@Inject
	public HelpOption helpOption;

	@Option(name = { "--user-agent" }, description = "User-Agent header value to send in HTTP requests.")
	public String userAgent;

	@Arguments(description="URL of the stream to check", required=true)
	List<String> urls;

	public static void main(String[] args) throws Exception {
		Main m = SingleCommand.singleCommand(Main.class).parse(args);
		if (m.helpOption.showHelpIfRequested()) {
			return;
		}
		m.run();
	}

	private void run() throws Exception {
		ResourceLeakDetector.setEnabled(false);

		AppBuilder b = new AppBuilder();
		if (userAgent != null) {
			b.setUserAgent(userAgent);
		}
		ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(16);
		Reporter rep = new ConsoleReporter();
		if (urls.size() == 1) {
			HlsMasterPlaylistProcessor processor = b.buildSingle(scheduledExecutor, rep);
			URI uri = new URI(urls.get(0));
			HlsMasterPlaylistContext ctx = processor.createContext(uri);
			Server server = ServerBuilder.create(ctx).build();
			processor.start(ctx);
			server.start();
		} else if (urls.size() == 2) {
			HlsRedundantStreamProcessor processor = b.buildRedundant(scheduledExecutor, rep);
			URI uri1 = new URI(urls.get(0));
			URI uri2 = new URI(urls.get(1));
			HlsRedundantStreamContext ctx = processor.createContext(uri1, uri2);
			processor.start(ctx);
		} else {
			System.err.println("wrong number of arguments, "+urls.size()+", expected 1 or 2.");
		}
		// TODO: what to do with the main thread in the meantime?
		while (true) {
			Thread.sleep(10_000);
		}
		//processor.stop(ctx);
	}
}
