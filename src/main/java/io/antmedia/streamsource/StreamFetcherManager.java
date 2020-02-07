package io.antmedia.streamsource;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import org.red5.server.api.scope.IScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.datastore.db.DataStore;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.datastore.db.types.Playlist;
import io.antmedia.muxer.MuxAdaptor;
import io.antmedia.rest.model.Result;
import io.antmedia.streamsource.StreamFetcher.IStreamFetcherListener;
import io.antmedia.streamsource.StreamFetcher.WorkerThread;
import io.vertx.core.Vertx;


/**
 * Organizes and checks stream fetcher and restarts them if it is required
 * @author davut
 *
 */
public class StreamFetcherManager {

	protected static Logger logger = LoggerFactory.getLogger(StreamFetcherManager.class);

	private int streamCheckerCount = 0;

	private WorkerThread thread;

	private Queue<StreamFetcher> streamFetcherList = new ConcurrentLinkedQueue<>();

	/**
	 * Time period in milli seconds for checking stream fetchers status, restart issues etc. 
	 */
	private int streamCheckerIntervalMs = 10000;

	private DataStore datastore;

	private IScope scope;

	private long streamFetcherScheduleJobName = -1L;

	private long playlistFetcherScheduleJobName = -1L;

	public long getPlaylistFetcherScheduleJobName() {
		return playlistFetcherScheduleJobName;
	}

	public void setPlaylistFetcherScheduleJobName(long playlistFetcherScheduleJobName) {
		this.playlistFetcherScheduleJobName = playlistFetcherScheduleJobName;
	}

	protected AtomicBoolean isJobRunning = new AtomicBoolean(false);

	private boolean restartStreamAutomatically = true;

	/**
	 * Time period in seconds for restarting stream fetchers
	 */
	private int restartStreamFetcherPeriodSeconds;

	private Vertx vertx;

	public StreamFetcherManager(Vertx vertx, DataStore datastore,IScope scope) {
		this.vertx = vertx;
		this.datastore = datastore;
		this.scope=scope;
	}

	public StreamFetcher make(Broadcast stream, IScope scope, Vertx vertx) {
		return new StreamFetcher(stream, scope, vertx);
	}

	public int getStreamCheckerInterval() {
		return streamCheckerIntervalMs;
	}


	/**
	 * Set stream checker interval, this value is used in periodically checking 
	 * the status of the stream fetchers
	 * 
	 * @param streamCheckerInterval, time period of the stream fetcher check interval in milliseconds
	 */
	public void setStreamCheckerInterval(int streamCheckerInterval) {
		this.streamCheckerIntervalMs = streamCheckerInterval;
	}

	/**
	 * Set stream fetcher restart period, this value is used in periodically stopping and starting
	 * stream fetchers. If this value is zero it will not restart stream fetchers
	 * 
	 * @param restartStreamFetcherPeriod, time period of the stream fetcher restart period in seconds
	 */
	public void setRestartStreamFetcherPeriod(int restartStreamFetcherPeriod) {
		this.restartStreamFetcherPeriodSeconds = restartStreamFetcherPeriod;	
	}


	public StreamFetcher startStreaming(@Nonnull Broadcast broadcast) {	

		//check if broadcast is already being fetching
		boolean alreadyFetching = false;
		for (StreamFetcher streamFetcher : streamFetcherList) {
			if (streamFetcher.getStream().getStreamId().equals(broadcast.getStreamId())) {
				alreadyFetching = true;
				break;
			}
		}

		StreamFetcher streamScheduler = null;
		if (!alreadyFetching) {

			try {
				streamScheduler =  make(broadcast, scope, vertx);
				streamScheduler.setRestartStream(restartStreamAutomatically);

				streamScheduler.startStream();

				if(!streamFetcherList.contains(streamScheduler)) {
					streamFetcherList.add(streamScheduler);
				}

				if (streamFetcherScheduleJobName == -1) {
					scheduleStreamFetcherJob();
				}
			}
			catch (Exception e) {
				streamScheduler = null;
				logger.error(e.getMessage());
			}
		}

		return streamScheduler;
	}


	public StreamFetcher playlistStartStreaming(@Nonnull Broadcast broadcast,StreamFetcher streamScheduler) {	

		//check if broadcast is already being fetching
		boolean alreadyFetching = false;
		for (StreamFetcher 	streamFetcherCheck : streamFetcherList) {
			if (streamFetcherCheck.getStream().getStreamId().equals(broadcast.getStreamId())) {
				alreadyFetching = true;
				break;
			}
		}

		if (!alreadyFetching) {

			try {

				streamScheduler.setRestartStream(false);
				streamScheduler.startStream();

				if(!streamFetcherList.contains(streamScheduler)) {
					streamFetcherList.add(streamScheduler);
				}

				if (streamFetcherScheduleJobName == -1) {
					scheduleStreamFetcherJob();
				}
			}
			catch (Exception e) {
				streamScheduler = null;
				logger.error(e.getMessage());
			}
		}

		return streamScheduler;
	}

	public Result stopStreaming(Broadcast stream) {
		logger.warn("inside of stopStreaming for {}", stream.getStreamId());
		Result result = new Result(false);

		for (StreamFetcher scheduler : streamFetcherList) {
			if (scheduler.getStream().getStreamId().equals(stream.getStreamId())) {
				scheduler.stopStream();
				streamFetcherList.remove(scheduler);
				result.setSuccess(true);
				break;
			}
		}
		return result;
	}

	public void stopCheckerJob() {
		if (streamFetcherScheduleJobName != -1) {
			vertx.cancelTimer(streamFetcherScheduleJobName);
			streamFetcherScheduleJobName = -1;
		}
	}

	public Result checkStreamUrlWithHTTP(String url) throws IOException {

		Result result = new Result(false);

		URL checkUrl = new URL(url);
		HttpURLConnection huc = (HttpURLConnection) checkUrl.openConnection();

		int responseCode = huc.getResponseCode();

		if(responseCode == HttpURLConnection.HTTP_OK) {
			result.setSuccess(true);
			return result;
		}

		return result;		
	}




	public void startPlaylistThread(Playlist playlist){

		// Get current stream in Playlist
		Broadcast playlistBroadcastItem = playlist.getBroadcastItemList().get(playlist.getCurrentPlayIndex());

		// Check Stream URL is valid.
		// If stream URL is not valid, it's trying next broadcast and trying.
		try {
			if(checkStreamUrlWithHTTP(playlistBroadcastItem.getStreamUrl()).isSuccess()) {

				// Create Stream Fetcher with Playlist Broadcast Item
				StreamFetcher streamScheduler = new StreamFetcher(playlistBroadcastItem, scope, vertx);

				// Update Playlist current playing status
				playlist.setPlaylistStatus(AntMediaApplicationAdapter.BROADCAST_STATUS_BROADCASTING);

				// Update broadcast current playing status
				playlist.getBroadcastItemList().get(playlist.getCurrentPlayIndex()).setStatus(AntMediaApplicationAdapter.BROADCAST_STATUS_BROADCASTING); 

				// Update Datastore current play broadcast
				datastore.editPlaylist(playlist.getPlaylistId(), playlist);

				streamScheduler.setStreamFetcherListener(new IStreamFetcherListener() {

					@Override
					public void streamFinished(IStreamFetcherListener listener) {

						stopStreaming(playlistBroadcastItem);
						
						// Get current playlist in database
						Playlist playlist = datastore.getPlaylist(playlistBroadcastItem.getStreamId());

						if(playlist.getPlaylistStatus().equals(AntMediaApplicationAdapter.BROADCAST_STATUS_BROADCASTING)) 
						{

							// Get Current Playlist Stream Index
							int currentStreamIndex = playlist.getCurrentPlayIndex()+1;

							if(playlist.getBroadcastItemList().size() == currentStreamIndex) 
							{

								//update playlist first broadcast
								playlist.setCurrentPlayIndex(0);
								currentStreamIndex = 0;
								datastore.editPlaylist(playlist.getPlaylistId(), playlist);

							}

							else {

								// update playlist currentPlayIndex value.
								playlist.setCurrentPlayIndex(currentStreamIndex);
								datastore.editPlaylist(playlist.getPlaylistId(), playlist);

							}
							
							logger.error("current play index -> " + currentStreamIndex);
							
							//update broadcast informations
							Broadcast fetchedBroadcast = playlist.getBroadcastItemList().get(currentStreamIndex);

							Result result = new Result(false);

							result.setSuccess(datastore.updateBroadcastFields(fetchedBroadcast.getStreamId(), fetchedBroadcast));

							StreamFetcher streamScheduler = new StreamFetcher(fetchedBroadcast,scope,vertx);

							streamScheduler.setStreamFetcherListener(listener);

							playlistStartStreaming(playlistBroadcastItem,streamScheduler);


						}

					}

				});

				playlistStartStreaming(playlistBroadcastItem,streamScheduler);

			}
			else {

				// Check already playlist broadcast list is one
				// If current playlist broadcast list size is 1, doesn't get a loop
				if(playlist.getBroadcastItemList().size() <= playlist.getCurrentPlayIndex()+1 ) {
					logger.info("All playlist URLs invalid");
				}
				else {

					// Try to next Broadcast Item 
					playlist.setCurrentPlayIndex(playlist.getCurrentPlayIndex()+1);

					// Update Db current playlist status
					datastore.editPlaylist(playlist.getPlaylistId(), playlist);

					startPlaylistThread(playlist);	
				}

			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}


	public void startStreams(List<Broadcast> streams) {

		for (int i = 0; i < streams.size(); i++) {
			startStreaming(streams.get(i));
		}

		scheduleStreamFetcherJob();
	}

	private void scheduleStreamFetcherJob() {
		if (streamFetcherScheduleJobName != -1) {
			vertx.cancelTimer(streamFetcherScheduleJobName);
		}

		streamFetcherScheduleJobName = vertx.setPeriodic(streamCheckerIntervalMs, l-> {

			int lastRestartCount = 0;

			if (!streamFetcherList.isEmpty()) {

				streamCheckerCount++;

				logger.debug("StreamFetcher Check Count:{}" , streamCheckerCount);

				int countToRestart = 0;
				if (restartStreamFetcherPeriodSeconds > 0) 
				{
					int streamCheckIntervalSec = streamCheckerIntervalMs / 1000;
					countToRestart = (streamCheckerCount * streamCheckIntervalSec) / restartStreamFetcherPeriodSeconds;
				}


				if (countToRestart > lastRestartCount) {
					lastRestartCount = countToRestart;
					logger.info("This is {} times that restarting streams", lastRestartCount);
					restartStreamFetchers();
				} else {
					checkStreamFetchersStatus();
				}
			}

		});

		logger.info("StreamFetcherSchedule job name {}", streamFetcherScheduleJobName);
	}

	public void checkStreamFetchersStatus() {
		for (StreamFetcher streamScheduler : streamFetcherList) {
			Broadcast stream = streamScheduler.getStream();

			if (!streamScheduler.isStreamAlive() && datastore != null && stream.getStreamId() != null) 
			{
				logger.info("Stream is not alive and setting quality to poor of stream: {} url: {}", stream.getStreamId(), stream.getStreamUrl());
				datastore.updateSourceQualityParameters(stream.getStreamId(), MuxAdaptor.QUALITY_POOR, 0, 0);
			}
		}
	}

	public void restartStreamFetchers() {
		for (StreamFetcher streamScheduler : streamFetcherList) {

			if (streamScheduler.isStreamAlive()) 
			{
				logger.info("Calling stop stream {}", streamScheduler.getStream().getStreamId());
				streamScheduler.stopStream();
			}
			else {
				logger.info("Stream is not alive {}", streamScheduler.getStream().getStreamId());
			}

			streamScheduler.startStream();
		}
	}

	public DataStore getDatastore() {
		return datastore;
	}

	public void setDatastore(DataStore datastore) {
		this.datastore = datastore;
	}

	public Queue<StreamFetcher> getStreamFetcherList() {
		return streamFetcherList;
	}

	public void setStreamFetcherList(Queue<StreamFetcher> streamFetcherList) {
		this.streamFetcherList = streamFetcherList;
	}



	public boolean isRestartStreamAutomatically() {
		return restartStreamAutomatically;
	}



	public void setRestartStreamAutomatically(boolean restartStreamAutomatically) {
		this.restartStreamAutomatically = restartStreamAutomatically;
	}



	public int getStreamCheckerCount() {
		return streamCheckerCount;
	}



	public void setStreamCheckerCount(int streamCheckerCount) {
		this.streamCheckerCount = streamCheckerCount;
	}

}
