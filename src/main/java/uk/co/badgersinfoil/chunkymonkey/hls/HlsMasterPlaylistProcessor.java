package uk.co.badgersinfoil.chunkymonkey.hls;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.chilicat.m3u8.Element;
import net.chilicat.m3u8.ParseException;
import net.chilicat.m3u8.Playlist;
import net.chilicat.m3u8.PlaylistInfo;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIUtils;
import uk.co.badgersinfoil.chunkymonkey.Reporter;
import uk.co.badgersinfoil.chunkymonkey.URILocator;

public class HlsMasterPlaylistProcessor {

	private ScheduledExecutorService scheduler;
	private HttpClient httpclient;
	private HlsMediaPlaylistProcessor mediaPlaylistProcessor;
	private HttpResponseChecker responseChecker = HttpResponseChecker.NULL;
	private Reporter rep = Reporter.NULL;
	private RequestConfig config;
	private boolean running;

	public HlsMasterPlaylistProcessor(ScheduledExecutorService scheduler, HttpClient httpclient, HlsMediaPlaylistProcessor mediaPlaylistProcessor) {
		this.scheduler = scheduler;
		this.httpclient = httpclient;
		this.mediaPlaylistProcessor = mediaPlaylistProcessor;
	}

	public void setReporter(Reporter rep) {
		this.rep = rep;
	}

	public HlsMasterPlaylistContext createContext(URI manifest) {
		return new HlsMasterPlaylistContext(manifest);
	}

	public void setResponseChecker(HttpResponseChecker responseChecker) {
		this.responseChecker = responseChecker;
	}

	public void start(final HlsMasterPlaylistContext ctx) {
		running = true;
		ctx.topLevelManifestFuture = scheduler.submit(new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				try {
					loadTopLevelManifest(ctx);
				} catch (Throwable t) {
					t.printStackTrace();
				}
				return null;
			}
		});
	}

	private void loadTopLevelManifest(final HlsMasterPlaylistContext ctx) {
		Playlist playlist = null;
		try {
			playlist = requestManifest(ctx);
		} catch (Exception e) {
			System.err.println("Loading top level manifest failed.  Will try again in 1 minute.");
			e.printStackTrace();
		}
		if (playlist == null) {
			if (running) {
				ctx.topLevelManifestFuture = scheduler.schedule(new Callable<Void>() {
					@Override
					public Void call() throws Exception {
						try {
							loadTopLevelManifest(ctx);
						} catch (Throwable e) {
							e.printStackTrace();
						}
						return null;
					}
				}, 1, TimeUnit.MINUTES);
			}
			return;
		}
		// TODO: handle the unlikely case that the kind of
		//       manifest changes between refreshes
		if (isMasterPlaylist(playlist)) {
			processMasterPlaylistEntries(ctx, playlist);
			ctx.lastTopLevel = playlist;
			if (running) {
				// reload in 5 mins in case it changes
				ctx.topLevelManifestFuture = scheduler.schedule(new Callable<Void>() {
					@Override
					public Void call() throws Exception {
						try {
							loadTopLevelManifest(ctx);
						} catch (Throwable e) {
							e.printStackTrace();
						}
						return null;
					}
				}, 5, TimeUnit.MINUTES);
			}
		} else {
			// this is not a real top-level manifest, but
			// as a special case handle it anyway,
			HlsMediaPlaylistContext mediaCtx = createMediaPlaylistContext(ctx, ctx.getManifestLocation(), null);
			ctx.mediaContexts.put(ctx.getManifestLocation(), mediaCtx);
			mediaPlaylistProcessor.process(mediaCtx, playlist);
		}
	}

	private void processMasterPlaylistEntries(HlsMasterPlaylistContext ctx,
	                                          Playlist playlist)
	{
		//if (ctx.lastTopLevel != null) {
		//	TODO: account for and changes
		//}
		for (Element e : playlist) {
			if (!ctx.mediaContexts.containsKey(e.getURI())) {
				addMediaPlaylist(ctx, e);
			}
		}
	}

	private void addMediaPlaylist(HlsMasterPlaylistContext ctx, Element e) {
		HlsMediaPlaylistContext mediaCtx = createMediaPlaylistContext(ctx, ctx.getManifestLocation().resolve(e.getURI()), e.getPlayListInfo());
		ctx.mediaContexts.put(e.getURI(), mediaCtx);
		mediaPlaylistProcessor.process(mediaCtx);
	}

	private HlsMediaPlaylistContext createMediaPlaylistContext(HlsMasterPlaylistContext ctx, URI manifest, PlaylistInfo playlistInfo) {
		return new HlsMediaPlaylistContext(ctx, manifest, playlistInfo);
	}


	private boolean isMasterPlaylist(Playlist playList) {
		return playList.getElements().get(0).isPlayList();
	}

	public Playlist requestManifest(final HlsMasterPlaylistContext ctx) throws IOException, ParseException {
		HttpClientContext context = HttpClientContext.create();
		HttpGet req = new HttpGet(ctx.getManifestLocation());
		if (config != null) {
			req.setConfig(config);
		}
		final URILocator loc = new URILocator(ctx.getManifestLocation());
		return new HttpExecutionWrapper<Playlist>(rep) {
			@Override
			protected Playlist handleResponse(HttpClientContext context, CloseableHttpResponse resp) throws IOException {
				List<URI> redirects = context.getRedirectLocations();
				if (redirects != null && !redirects.isEmpty()) {
					URI finalUri = null;
					try {
						finalUri = URIUtils.resolve(ctx.getManifestSpecified(), context.getTargetHost(), redirects);
						ctx.setManifestRedirectLocation(finalUri);
					} catch (URISyntaxException e) {
						e.printStackTrace();
					}
					rep.carp(new URILocator(ctx.getManifestSpecified(), loc), "Followed %d redirect(s) to: %s", redirects.size(), finalUri==null ? redirects.get(redirects.size()-1) : finalUri);
				}
				responseChecker.check(loc, resp, context);
				InputStream stream = resp.getEntity().getContent();
				try {
					return Playlist.parse(stream);
				} catch (ParseException e) {
					throw new IOException(e);
				} finally {
					stream.close();
				}
			}
		}.execute(httpclient, req, loc);
	}

	public void stop(final HlsMasterPlaylistContext ctx) {
		running = false;
		ctx.topLevelManifestFuture.cancel(true);
		// TODO: stop media playlist processing too
	}

	public void setConfig(RequestConfig config) {
		this.config = config;
	}
}