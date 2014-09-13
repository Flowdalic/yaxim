package org.yaxim.androidclient.service;

import gnu.inet.encoding.IDNAException;

import java.io.File;
import java.text.Collator;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import de.duenndns.ssl.MemorizingTrustManager;

import org.jivesoftware.smackx.iqregister.AccountManager;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.RosterGroup;
import org.jivesoftware.smack.RosterListener;
import org.jivesoftware.smack.SmackConfiguration;
import org.jivesoftware.smack.SmackException.NoResponseException;
import org.jivesoftware.smack.SmackException.NotConnectedException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.IQ.Type;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Presence.Mode;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.sm.StreamManagementException.StreamManagementNotEnabledException;
import org.jivesoftware.smack.util.DNSUtil;
import org.jivesoftware.smack.util.StringTransformer;
import org.jivesoftware.smackx.caps.EntityCapsManager;
import org.jivesoftware.smackx.caps.cache.SimpleDirectoryPersistentCache;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.carbons.packet.CarbonExtension;
import org.jivesoftware.smackx.delay.packet.DelayInformation;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo;
import org.jivesoftware.smackx.iqversion.VersionManager;
import org.jivesoftware.smackx.ping.packet.Ping;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.muc.DiscussionHistory;
import org.jivesoftware.smackx.muc.InvitationListener;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.Occupant;
import org.jivesoftware.smackx.muc.RoomInfo;
import org.jivesoftware.smackx.muc.packet.MUCUser;
import org.jivesoftware.smackx.forward.Forwarded;
import org.jivesoftware.smackx.receipts.DeliveryReceipt;
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager;
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest;
import org.jivesoftware.smackx.receipts.ReceiptReceivedListener;
import org.jxmpp.util.XmppStringUtils;
import org.yaxim.androidclient.YaximApplication;
import org.yaxim.androidclient.data.ChatProvider;
import org.yaxim.androidclient.data.RosterProvider;
import org.yaxim.androidclient.data.YaximConfiguration;
import org.yaxim.androidclient.data.ChatProvider.ChatConstants;
import org.yaxim.androidclient.data.RosterProvider.RosterConstants;
import org.yaxim.androidclient.exceptions.YaximXMPPException;
import org.yaxim.androidclient.util.ConnectionState;
import org.yaxim.androidclient.util.LogConstants;
import org.yaxim.androidclient.util.StatusMode;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.telephony.gsm.SmsMessage.MessageClass;
import android.util.Log;

public class SmackableImp implements Smackable {
	final static private String TAG = "yaxim.SmackableImp";

	final static private int PACKET_TIMEOUT = 30000;

	final static private String[] SEND_OFFLINE_PROJECTION = new String[] {
			ChatConstants._ID, ChatConstants.JID,
			ChatConstants.MESSAGE, ChatConstants.DATE, ChatConstants.PACKET_ID };
	final static private String SEND_OFFLINE_SELECTION =
			ChatConstants.DIRECTION + " = " + ChatConstants.OUTGOING + " AND " +
			ChatConstants.DELIVERY_STATUS + " = " + ChatConstants.DS_NEW;

	static final DiscoverInfo.Identity YAXIM_IDENTITY = new DiscoverInfo.Identity("client",
					YaximApplication.XMPP_IDENTITY_NAME,
					YaximApplication.XMPP_IDENTITY_TYPE);

	static File capsCacheDir = null; ///< this is used to cache if we already initialized EntityCapsCache

	static {
		ServiceDiscoveryManager.setDefaultIdentity(YAXIM_IDENTITY);

		// initialize smack defaults before any connections are created
		SmackConfiguration.setDefaultPacketReplyTimeout(PACKET_TIMEOUT);
//		SmackConfiguration.setDefaultPingInterval(0);

		DNSUtil.setIdnaTransformer(new StringTransformer() {
			@Override
			public String transform(String string) {
				try {
					return gnu.inet.encoding.IDNA.toASCII(string);
				} catch (IDNAException e) {
					Log.w("Could not perform IDNA transformation", e);
					return string;
				}
			}
		});
	}

	private final YaximConfiguration mConfig;
	private ConnectionConfiguration mXMPPConfig;
	private XMPPTCPConnection mXMPPConnection;
	private Thread mConnectingThread;
	private Object mConnectingThreadMutex = new Object();


	private ConnectionState mRequestedState = ConnectionState.OFFLINE;
	private ConnectionState mState = ConnectionState.OFFLINE;
	private String mLastError;
	
	private XMPPServiceCallback mServiceCallBack;
	private Roster mRoster;
	private RosterListener mRosterListener;
	private PacketListener mPacketListener;
	private PacketListener mPresenceListener;
	private ConnectionListener mConnectionListener;

	private final ContentResolver mContentResolver;

	private AlarmManager mAlarmManager;
	private PacketListener mPongListener;
	private String mPingID;
	private long mPingTimestamp;

	private PendingIntent mPingAlarmPendIntent;
	private PendingIntent mPongTimeoutAlarmPendIntent;
	private static final String PING_ALARM = "org.yaxim.androidclient.PING_ALARM";
	private static final String PONG_TIMEOUT_ALARM = "org.yaxim.androidclient.PONG_TIMEOUT_ALARM";
	private Intent mPingAlarmIntent = new Intent(PING_ALARM);
	private Intent mPongTimeoutAlarmIntent = new Intent(PONG_TIMEOUT_ALARM);
	private Service mService;

	private PongTimeoutAlarmReceiver mPongTimeoutAlarmReceiver = new PongTimeoutAlarmReceiver();
	private BroadcastReceiver mPingAlarmReceiver = new PingAlarmReceiver();
	
	private final HashSet<String> mucJIDs = new HashSet<String>();
	private Map<String, MultiUserChat> multiUserChats;


	public SmackableImp(YaximConfiguration config,
			ContentResolver contentResolver,
			Service service) {
		this.mConfig = config;
		this.mContentResolver = contentResolver;
		this.mService = service;
		this.mAlarmManager = (AlarmManager)mService.getSystemService(Context.ALARM_SERVICE);
	}
		
	// this code runs a DNS resolver, might be blocking
	private synchronized void initXMPPConnection() {
		// allow custom server / custom port to override SRV record
		if (mConfig.customServer.length() > 0)
			mXMPPConfig = new ConnectionConfiguration(mConfig.customServer,
					mConfig.port, mConfig.server);
		else
			mXMPPConfig = new ConnectionConfiguration(mConfig.server); // use SRV
		mXMPPConfig.setReconnectionAllowed(false);
		mXMPPConfig.setSendPresence(false);
		mXMPPConfig.setCompressionEnabled(false); // disable for now
		mXMPPConfig.setDebuggerEnabled(mConfig.smackdebug);
		if (mConfig.require_ssl)
			this.mXMPPConfig.setSecurityMode(ConnectionConfiguration.SecurityMode.required);

		// register MemorizingTrustManager for HTTPS
		try {
			SSLContext sc = SSLContext.getInstance("TLS");
			MemorizingTrustManager mtm = YaximApplication.getApp(mService).mMTM;
			sc.init(null, new X509TrustManager[] { mtm },
					new java.security.SecureRandom());
			this.mXMPPConfig.setCustomSSLContext(sc);
			this.mXMPPConfig.setHostnameVerifier(mtm.wrapHostnameVerifier(
						new org.apache.http.conn.ssl.StrictHostnameVerifier()));
		} catch (java.security.GeneralSecurityException e) {
			debugLog("initialize MemorizingTrustManager: " + e);
		}

		mXMPPConnection = new XMPPTCPConnection(mXMPPConfig);
		mConfig.reconnect_required = false;

		multiUserChats = new HashMap<String, MultiUserChat>();
		initServiceDiscovery();
	}

	// blocking, run from a thread!
	public boolean doConnect(boolean create_account) throws YaximXMPPException {
		mRequestedState = ConnectionState.ONLINE;
		updateConnectionState(ConnectionState.CONNECTING);
		if (mXMPPConnection == null || mConfig.reconnect_required)
			initXMPPConnection();
		tryToConnect(create_account);
		// actually, authenticated must be true now, or an exception must have
		// been thrown.
		if (isAuthenticated()) {
			registerMessageListener();
			registerPresenceListener();
			registerPongListener();
			syncDbRooms();
			sendOfflineMessages();
			sendUserWatching();
			// we need to "ping" the service to let it know we are actually
			// connected, even when no roster entries will come in
			updateConnectionState(ConnectionState.ONLINE);
		} else throw new YaximXMPPException("SMACK connected, but authentication failed");
		return true;
	}

	// BLOCKING, call on a new Thread!
	private void updateConnectingThread(Thread new_thread) {
		synchronized(mConnectingThreadMutex) {
			if (mConnectingThread == null) {
				mConnectingThread = new_thread;
			} else try {
				Log.d(TAG, "updateConnectingThread: old thread is still running, killing it.");
				mConnectingThread.interrupt();
				mConnectingThread.join(50);
			} catch (InterruptedException e) {
				Log.d(TAG, "updateConnectingThread: failed to join(): " + e);
			} finally {
				mConnectingThread = new_thread;
			}
		}
	}
	private void finishConnectingThread() {
		synchronized(mConnectingThreadMutex) {
			mConnectingThread = null;
		}
	}

	/** Non-blocking, synchronized function to connect/disconnect XMPP.
	 * This code is called from outside and returns immediately. The actual work
	 * is done on a background thread, and notified via callback.
	 * @param new_state The state to transition into. Possible values:
	 * 	OFFLINE to properly close the connection
	 * 	ONLINE to connect
	 * 	DISCONNECTED when network goes down
	 * @param create_account When going online, try to register an account.
	 */
	@Override
	public synchronized void requestConnectionState(ConnectionState new_state, final boolean create_account) {
		Log.d(TAG, "requestConnState: " + mState + " -> " + new_state + (create_account ? " create_account!" : ""));
		mRequestedState = new_state;
		if (new_state == mState)
			return;
		switch (new_state) {
		case ONLINE:
			switch (mState) {
			case RECONNECT_DELAYED:
				// TODO: cancel timer
			case RECONNECT_NETWORK:
			case OFFLINE:
				// update state before starting thread to prevent race conditions
				updateConnectionState(ConnectionState.CONNECTING);

				// register ping (connection) timeout handler: 2*PACKET_TIMEOUT(30s) + 3s
				registerPongTimeout(2*PACKET_TIMEOUT + 3000, "connection");

				new Thread() {
					@Override
					public void run() {
						updateConnectingThread(this);
						try {
							doConnect(create_account);
						} catch (IllegalArgumentException e) {
							// this might happen when DNS resolution in ConnectionConfiguration fails
							onDisconnected(e);
						} catch (YaximXMPPException e) {
							onDisconnected(e);
						} finally {
							mAlarmManager.cancel(mPongTimeoutAlarmPendIntent);
							finishConnectingThread();
						}
					}
				}.start();
				break;
			case CONNECTING:
			case DISCONNECTING:
				// ignore all other cases
				break;
			}
			break;
		case DISCONNECTED:
			// spawn thread to do disconnect
			if (mState == ConnectionState.ONLINE) {
				// update state before starting thread to prevent race conditions
				updateConnectionState(ConnectionState.DISCONNECTING);

				// register ping (connection) timeout handler: PACKET_TIMEOUT(30s)
				registerPongTimeout(PACKET_TIMEOUT, "forced disconnect");

				new Thread() {
					public void run() {
						updateConnectingThread(this);
						mXMPPConnection.instantShutdown();
						onDisconnected("forced disconnect completed");
						finishConnectingThread();
						//updateConnectionState(ConnectionState.OFFLINE);
					}
				}.start();
			}
			break;
		case OFFLINE:
			switch (mState) {
			case CONNECTING:
			case ONLINE:
				// update state before starting thread to prevent race conditions
				updateConnectionState(ConnectionState.DISCONNECTING);

				// register ping (connection) timeout handler: PACKET_TIMEOUT(30s)
				registerPongTimeout(PACKET_TIMEOUT, "manual disconnect");

				// spawn thread to do disconnect
				new Thread() {
					public void run() {
						updateConnectingThread(this);
						try {
							mXMPPConnection.disconnect();
						} catch (NotConnectedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						mAlarmManager.cancel(mPongTimeoutAlarmPendIntent);
						// we should reset XMPPConnection the next time
						mConfig.reconnect_required = true;
						finishConnectingThread();
						// reconnect if it was requested in the meantime
						if (mRequestedState == ConnectionState.ONLINE)
							requestConnectionState(ConnectionState.ONLINE);
					}
				}.start();
				break;
			case DISCONNECTING:
				break;
			case RECONNECT_DELAYED:
				// TODO: clear timer
			case RECONNECT_NETWORK:
				updateConnectionState(ConnectionState.OFFLINE);
			}
			break;
		case RECONNECT_NETWORK:
		case RECONNECT_DELAYED:
			switch (mState) {
			case DISCONNECTED:
			case RECONNECT_NETWORK:
			case RECONNECT_DELAYED:
				updateConnectionState(new_state);
				break;
			default:
				throw new IllegalArgumentException("Can not go from " + mState + " to " + new_state);
			}
		}
	}
	@Override
	public void requestConnectionState(ConnectionState new_state) {
		requestConnectionState(new_state, false);
	}

	@Override
	public ConnectionState getConnectionState() {
		return mState;
	}

	// called at the end of a state transition
	private synchronized void updateConnectionState(ConnectionState new_state) {
		if (new_state == ConnectionState.ONLINE || new_state == ConnectionState.CONNECTING)
			mLastError = null;
		Log.d(TAG, "updateConnectionState: " + mState + " -> " + new_state + " (" + mLastError + ")");
		if (new_state == mState)
			return;
		mState = new_state;
		if (mServiceCallBack != null)
			mServiceCallBack.connectionStateChanged();
	}
	private void initServiceDiscovery() {
		// init Entity Caps manager with storage in app's cache dir
		if (capsCacheDir == null) {
			capsCacheDir = new File(mService.getCacheDir(), "entity-caps-cache");
			capsCacheDir.mkdirs();
			EntityCapsManager.setPersistentCache(new SimpleDirectoryPersistentCache(capsCacheDir));
		}

		// set Version for replies
		String app_name = mService.getString(org.yaxim.androidclient.R.string.app_name);
		String build_revision = mService.getString(org.yaxim.androidclient.R.string.build_revision);
		VersionManager.setDefaultVersion(app_name, build_revision, "Android");

		// reference DeliveryReceiptManager, add listener
		DeliveryReceiptManager dm = DeliveryReceiptManager.getInstanceFor(mXMPPConnection);
		dm.enableAutoReceipts();
		dm.addReceiptReceivedListener(new ReceiptReceivedListener() { // DOES NOT WORK IN CARBONS
			public void onReceiptReceived(String fromJid, String toJid, String receiptId) {
				Log.d(TAG, "got delivery receipt for " + receiptId);
				changeMessageDeliveryStatus(receiptId, ChatConstants.DS_ACKED);
			}});
	}

	public void addRosterItem(String user, String alias, String group)
			throws YaximXMPPException {
		tryToAddRosterEntry(user, alias, group);
	}

	public void removeRosterItem(String user) throws YaximXMPPException {
		debugLog("removeRosterItem(" + user + ")");

		tryToRemoveRosterEntry(user);
	}

	public void renameRosterItem(String user, String newName)
			throws YaximXMPPException {
		RosterEntry rosterEntry = mRoster.getEntry(user);

		if (!(newName.length() > 0) || (rosterEntry == null)) {
			throw new YaximXMPPException("JabberID to rename is invalid!");
		}
		try {
			rosterEntry.setName(newName);
		} catch (NotConnectedException e) {
			throw new YaximXMPPException(e.getLocalizedMessage());
		}
	}

	public void addRosterGroup(String group) {
		mRoster.createGroup(group);
	}

	public void renameRosterGroup(String group, String newGroup) {
		RosterGroup groupToRename = mRoster.getGroup(group);
		try {
			groupToRename.setName(newGroup);
		} catch (NotConnectedException e) {
			throw new IllegalStateException(e);
		}
	}

	public void moveRosterItemToGroup(String user, String group)
			throws YaximXMPPException {
		tryToMoveRosterEntryToGroup(user, group);
	}

	public void sendPresenceRequest(String user, String type) {
		// HACK: remove the fake roster entry added by handleIncomingSubscribe()
		if ("unsubscribed".equals(type))
			deleteRosterEntryFromDB(user);
		Presence response = new Presence(Presence.Type.valueOf(type));
		response.setTo(user);
		try {
			mXMPPConnection.sendPacket(response);
		} catch (NotConnectedException e) {
			throw new IllegalStateException(e);
		}
	}
	
	@Override
	public String changePassword(String newPassword) {
		try {
			AccountManager.getInstance(mXMPPConnection).changePassword(newPassword);
			return "OK"; //HACK: hard coded string to differentiate from failure modes
		} catch (XMPPErrorException e) {
			if (e.getXMPPError() != null)
				return e.getXMPPError().toString();
			else
				return e.getLocalizedMessage();
		} catch (NoResponseException e) {
			throw new IllegalStateException(e);
		} catch (NotConnectedException e) {
			throw new IllegalStateException(e);
		}
	}

	private void onDisconnected(String reason) {
		unregisterPongListener();
		mLastError = reason;
		updateConnectionState(ConnectionState.DISCONNECTED);
	}
	private void onDisconnected(Throwable reason) {
		Log.e(TAG, "onDisconnected: " + reason);
		reason.printStackTrace();
		// iterate through to the deepest exception
		while (reason.getCause() != null)
			reason = reason.getCause();
		onDisconnected(reason.getLocalizedMessage());
	}

	private void tryToConnect(boolean create_account) throws YaximXMPPException {
		try {
			if (mXMPPConnection.isConnected()) {
				try {
					mXMPPConnection.instantShutdown(); // blocking shutdown prior to re-connection
				} catch (Exception e) {
					debugLog("conn.shutdown() failed: " + e);
				}
			}
			registerRosterListener();

			if (mConnectionListener != null)
				mXMPPConnection.removeConnectionListener(mConnectionListener);
			mConnectionListener = new ConnectionListener() {
				public void connectionClosedOnError(Exception e) {
					// XXX: this is the only callback we get from errors, so
					// we need to check for non-resumability and work around
					// here:
					if (!mXMPPConnection.isSmResumptionPossible()) {
						multiUserChats.clear();
					}
					onDisconnected(e);
				}
				public void connectionClosed() {
					// TODO: fix reconnect when we got kicked by the server or SM failed!
					//onDisconnected(null);
					multiUserChats.clear();
					updateConnectionState(ConnectionState.OFFLINE);
				}
				@Override
				public void connected(XMPPConnection connection) {}
				@Override
				public void authenticated(XMPPConnection connection) {}
				@Override
				public void reconnectingIn(int seconds) {}
				@Override
				public void reconnectionSuccessful() {}
				@Override
				public void reconnectionFailed(Exception e) {}
			};
			mXMPPConnection.addConnectionListener(mConnectionListener);

			mXMPPConnection.connect();
			// SMACK auto-logins if we were authenticated before
			if (!mXMPPConnection.isAuthenticated()) {
				if (create_account) {
					Log.d(TAG, "creating new server account...");
					AccountManager am = AccountManager.getInstance(mXMPPConnection);
					am.createAccount(mConfig.userName, mConfig.password);
				}
				mXMPPConnection.login(mConfig.userName, mConfig.password,
						mConfig.ressource);
			}
			Log.d(TAG, "SM: can resume = " + mXMPPConnection.isSmResumptionPossible());
//			if (need_bind) {
//				mStreamHandler.notifyInitialLogin();
//				setStatusFromConfig();
//			}

		} catch (Exception e) {
			// actually we just care for IllegalState or NullPointer or XMPPEx.
			throw new YaximXMPPException("tryToConnect failed", e);
		}
	}

	private void tryToMoveRosterEntryToGroup(String userName, String groupName)
			throws YaximXMPPException {

		RosterGroup rosterGroup = getRosterGroup(groupName);
		RosterEntry rosterEntry = mRoster.getEntry(userName);

		removeRosterEntryFromGroups(rosterEntry);

		if (groupName.length() == 0)
			return;
		else {
			try {
				rosterGroup.addEntry(rosterEntry);
			} catch (XMPPException e) {
				throw new YaximXMPPException("tryToMoveRosterEntryToGroup", e);
			} catch (NoResponseException e) {
				throw new YaximXMPPException("tryToMoveRosterEntryToGroup", e);
			} catch (NotConnectedException e) {
				throw new YaximXMPPException("tryToMoveRosterEntryToGroup", e);
			}
		}
	}

	private RosterGroup getRosterGroup(String groupName) {
		RosterGroup rosterGroup = mRoster.getGroup(groupName);

		// create group if unknown
		if ((groupName.length() > 0) && rosterGroup == null) {
			rosterGroup = mRoster.createGroup(groupName);
		}
		return rosterGroup;

	}

	private void removeRosterEntryFromGroups(RosterEntry rosterEntry)
			throws YaximXMPPException {
		Collection<RosterGroup> oldGroups = rosterEntry.getGroups();

		for (RosterGroup group : oldGroups) {
			tryToRemoveUserFromGroup(group, rosterEntry);
		}
	}

	private void tryToRemoveUserFromGroup(RosterGroup group,
			RosterEntry rosterEntry) throws YaximXMPPException {
		try {
			group.removeEntry(rosterEntry);
		} catch (XMPPException e) {
			throw new YaximXMPPException("tryToRemoveUserFromGroup", e);
		} catch (NoResponseException e) {
			throw new YaximXMPPException("tryToMoveRosterEntryToGroup", e);
		} catch (NotConnectedException e) {
			throw new YaximXMPPException("tryToMoveRosterEntryToGroup", e);
		}
	}

	private void tryToRemoveRosterEntry(String user) throws YaximXMPPException {
		try {
			RosterEntry rosterEntry = mRoster.getEntry(user);

			if (rosterEntry != null) {
				// first, unsubscribe the user
				Presence unsub = new Presence(Presence.Type.unsubscribed);
				unsub.setTo(rosterEntry.getUser());
				mXMPPConnection.sendPacket(unsub);
				// then, remove from roster
				mRoster.removeEntry(rosterEntry);
			}
		} catch (Exception e) {
			throw new YaximXMPPException("tryToRemoveRosterEntry", e);
		}
	}

	private void tryToAddRosterEntry(String user, String alias, String group)
			throws YaximXMPPException {
		try {
			mRoster.createEntry(user, alias, new String[] { group });
		} catch (Exception e) {
			throw new YaximXMPPException("tryToAddRosterEntry", e);
		}
	}

	private void removeOldRosterEntries() {
		Log.d(TAG, "removeOldRosterEntries()");
		Collection<RosterEntry> rosterEntries = mRoster.getEntries();
		StringBuilder exclusion = new StringBuilder(RosterConstants.JID + " NOT IN (");
		boolean first = true;
		
		for (RosterEntry rosterEntry : rosterEntries) {
			updateRosterEntryInDB(rosterEntry);
			if (first)
				first = false;
			else
				exclusion.append(",");
			exclusion.append("'").append(rosterEntry.getUser()).append("'");
		}
		
		exclusion.append(") AND "+RosterConstants.GROUP+" NOT IN ('MUCs');");
		int count = mContentResolver.delete(RosterProvider.CONTENT_URI, exclusion.toString(), null);
		Log.d(TAG, "deleted " + count + " old roster entries");
	}

	// HACK: add an incoming subscription request as a fake roster entry
	private void handleIncomingSubscribe(Presence request) {
		final ContentValues values = new ContentValues();

		values.put(RosterConstants.JID, request.getFrom());
		values.put(RosterConstants.ALIAS, request.getFrom());
		values.put(RosterConstants.GROUP, "");

		values.put(RosterConstants.STATUS_MODE, getStatusInt(request));
		values.put(RosterConstants.STATUS_MESSAGE, request.getStatus());
		
		Uri uri = mContentResolver.insert(RosterProvider.CONTENT_URI, values);
		debugLog("handleIncomingSubscribe: faked " + uri);
	}

	public void setStatusFromConfig() {
		// TODO: only call this when carbons changed, not on every presence change
		try {
			CarbonManager.getInstanceFor(mXMPPConnection).sendCarbonsEnabled(mConfig.messageCarbons);
		} catch (NotConnectedException e) {
			throw new IllegalStateException(e);
		}

		Presence presence = new Presence(Presence.Type.available);
		Mode mode = Mode.valueOf(mConfig.statusMode);
		presence.setMode(mode);
		presence.setStatus(mConfig.statusMessage);
		presence.setPriority(mConfig.priority);
		try {
			mXMPPConnection.sendPacket(presence);
		} catch (NotConnectedException e) {
			throw new IllegalStateException(e);
		}
		mConfig.presence_required = false;
	}

	public void sendOfflineMessages() {
		Cursor cursor = mContentResolver.query(ChatProvider.CONTENT_URI,
				SEND_OFFLINE_PROJECTION, SEND_OFFLINE_SELECTION,
				null, null);
		final int      _ID_COL = cursor.getColumnIndexOrThrow(ChatConstants._ID);
		final int      JID_COL = cursor.getColumnIndexOrThrow(ChatConstants.JID);
		final int      MSG_COL = cursor.getColumnIndexOrThrow(ChatConstants.MESSAGE);
		final int       TS_COL = cursor.getColumnIndexOrThrow(ChatConstants.DATE);
		final int PACKETID_COL = cursor.getColumnIndexOrThrow(ChatConstants.PACKET_ID);
		ContentValues mark_sent = new ContentValues();
		mark_sent.put(ChatConstants.DELIVERY_STATUS, ChatConstants.DS_SENT_OR_READ);
		while (cursor.moveToNext()) {
			int _id = cursor.getInt(_ID_COL);
			String toJID = cursor.getString(JID_COL);
			String message = cursor.getString(MSG_COL);
			String packetID = cursor.getString(PACKETID_COL);
			long ts = cursor.getLong(TS_COL);
			Log.d(TAG, "sendOfflineMessages: " + toJID + " > " + message);
			final Message newMessage = new Message(toJID, Message.Type.chat);
			newMessage.setBody(message);
			DelayInformation delay = new DelayInformation(new Date(ts));
			newMessage.addExtension(delay);
			newMessage.addExtension(new DeliveryReceiptRequest());
			if ((packetID != null) && (packetID.length() > 0)) {
				newMessage.setPacketID(packetID);
			} else {
				packetID = newMessage.getPacketID();
				mark_sent.put(ChatConstants.PACKET_ID, packetID);
			}
			Uri rowuri = Uri.parse("content://" + ChatProvider.AUTHORITY
				+ "/" + ChatProvider.TABLE_NAME + "/" + _id);
			mContentResolver.update(rowuri, mark_sent,
						null, null);
			try {
				mXMPPConnection.sendPacket(newMessage);
			} catch (NotConnectedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			// must be after marking delivered, otherwise it may override the SendFailListener
		}
		cursor.close();
	}

	public static void sendOfflineMessage(ContentResolver cr, String toJID, String message) {
		ContentValues values = new ContentValues();
		values.put(ChatConstants.DIRECTION, ChatConstants.OUTGOING);
		values.put(ChatConstants.JID, toJID);
		values.put(ChatConstants.MESSAGE, message);
		values.put(ChatConstants.DELIVERY_STATUS, ChatConstants.DS_NEW);
		values.put(ChatConstants.DATE, System.currentTimeMillis());

		cr.insert(ChatProvider.CONTENT_URI, values);
	}

	public void sendReceipt(String toJID, String id) {
		Log.d(TAG, "sending XEP-0184 ack to " + toJID + " id=" + id);
		final Message ack = new Message(toJID, Message.Type.normal);
		ack.addExtension(new DeliveryReceipt(id));
		try {
			mXMPPConnection.sendPacket(ack);
		} catch (NotConnectedException e) {
			throw new IllegalStateException(e);
		}
	}

	public void sendMessage(String toJID, String message) {
		final Message newMessage = new Message(toJID, Message.Type.chat);
		newMessage.setBody(message);
		newMessage.addExtension(new DeliveryReceiptRequest());
		if (isAuthenticated()) {
			addChatMessageToDB(ChatConstants.OUTGOING, toJID, message, ChatConstants.DS_SENT_OR_READ,
					System.currentTimeMillis(), newMessage.getPacketID());
			try {
				mXMPPConnection.sendPacket(newMessage);
			} catch (NotConnectedException e) {
				throw new IllegalStateException(e);
			}

			if(mucJIDs.contains(toJID)) {
				sendMucMessage(toJID, message);
			} else {
				addChatMessageToDB(ChatConstants.OUTGOING, toJID, message, ChatConstants.DS_SENT_OR_READ,
						System.currentTimeMillis(), newMessage.getPacketID());
				try {
					mXMPPConnection.sendPacket(newMessage);
				} catch (NotConnectedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		} else {
			// send offline -> store to DB
			addChatMessageToDB(ChatConstants.OUTGOING, toJID, message, ChatConstants.DS_NEW,
					System.currentTimeMillis(), newMessage.getPacketID());
		}
	}

	public boolean isAuthenticated() {
		if (mXMPPConnection != null) {
			return (mXMPPConnection.isConnected() && mXMPPConnection
					.isAuthenticated());
		}
		return false;
	}

	public void registerCallback(XMPPServiceCallback callBack) {
		this.mServiceCallBack = callBack;
		mService.registerReceiver(mPingAlarmReceiver, new IntentFilter(PING_ALARM));
		mService.registerReceiver(mPongTimeoutAlarmReceiver, new IntentFilter(PONG_TIMEOUT_ALARM));
	}

	public void unRegisterCallback() {
		debugLog("unRegisterCallback()");
		// remove callbacks _before_ tossing old connection
		try {
			mXMPPConnection.getRoster().removeRosterListener(mRosterListener);
			mXMPPConnection.removePacketListener(mPacketListener);
			mXMPPConnection.removePacketListener(mPresenceListener);

			mXMPPConnection.removePacketListener(mPongListener);
			unregisterPongListener();
		} catch (Exception e) {
			// ignore it!
			e.printStackTrace();
		}
		requestConnectionState(ConnectionState.OFFLINE);
		setStatusOffline();
		mService.unregisterReceiver(mPingAlarmReceiver);
		mService.unregisterReceiver(mPongTimeoutAlarmReceiver);
//		multiUserChats.clear(); // TODO: right place
		this.mServiceCallBack = null;
	}
	
	public String getNameForJID(String jid) {
		RosterEntry re = mRoster.getEntry(jid);
		if (null != re && null != re.getName() && re.getName().length() > 0) {
			return re.getName();
		} else if (mucJIDs.contains(jid)) {
			// query the DB as we do not have the room name in memory
			Cursor c = mContentResolver.query(RosterProvider.CONTENT_URI, new String[] { RosterConstants.ALIAS },
					RosterConstants.JID + " = ?", new String[] { jid }, null);
			String result = jid;
			if (c.moveToFirst())
				result = c.getString(0);
			c.close();
			return result;
		} else {
			return jid;
		}			
	}

	private void setStatusOffline() {
		ContentValues values = new ContentValues();
		values.put(RosterConstants.STATUS_MODE, StatusMode.offline.ordinal());
		mContentResolver.update(RosterProvider.CONTENT_URI, values, null, null);
	}

	private void registerRosterListener() {
		// flush roster on connecting.
		mRoster = mXMPPConnection.getRoster();
		mRoster.setSubscriptionMode(Roster.SubscriptionMode.manual);

		if (mRosterListener != null)
			mRoster.removeRosterListener(mRosterListener);

		mRosterListener = new RosterListener() {
			private boolean first_roster = true;

			public void entriesAdded(Collection<String> entries) {
				debugLog("entriesAdded(" + entries + ")");

				ContentValues[] cvs = new ContentValues[entries.size()];
				int i = 0;
				for (String entry : entries) {
					RosterEntry rosterEntry = mRoster.getEntry(entry);
					cvs[i++] = getContentValuesForRosterEntry(rosterEntry);
				}
				mContentResolver.bulkInsert(RosterProvider.CONTENT_URI, cvs);
				// when getting the roster in the beginning, remove remains of old one
				if (first_roster) {
					removeOldRosterEntries();
					first_roster = false;
				}
				debugLog("entriesAdded() done");
			}

			public void entriesDeleted(Collection<String> entries) {
				debugLog("entriesDeleted(" + entries + ")");

				for (String entry : entries) {
					deleteRosterEntryFromDB(entry);
				}
			}

			public void entriesUpdated(Collection<String> entries) {
				debugLog("entriesUpdated(" + entries + ")");

				for (String entry : entries) {
					RosterEntry rosterEntry = mRoster.getEntry(entry);
					updateRosterEntryInDB(rosterEntry);
				}
			}

			public void presenceChanged(Presence presence) {
				debugLog("presenceChanged(" + presence.getFrom() + "): " + presence);

				String jabberID = XmppStringUtils.parseBareAddress(presence.getFrom());
				RosterEntry rosterEntry = mRoster.getEntry(jabberID);
				if (rosterEntry != null)
					updateRosterEntryInDB(rosterEntry);
			}
		};
		mRoster.addRosterListener(mRosterListener);
	}

	private String[] getJabberID(String from) {
		if(from.contains("/")) {
			String[] res = from.split("/");
			return new String[] { res[0], res[1] };
		} else {
			return new String[] {from, ""};
		}
	}

	public boolean changeMessageDeliveryStatus(String packetID, int new_status) {
		ContentValues cv = new ContentValues();
		cv.put(ChatConstants.DELIVERY_STATUS, new_status);
		Uri rowuri = Uri.parse("content://" + ChatProvider.AUTHORITY + "/"
				+ ChatProvider.TABLE_NAME);
		return mContentResolver.update(rowuri, cv,
				ChatConstants.PACKET_ID + " = ? AND " +
				ChatConstants.DELIVERY_STATUS + " != " + ChatConstants.DS_ACKED + " AND " +
				ChatConstants.DIRECTION + " = " + ChatConstants.OUTGOING,
				new String[] { packetID }) > 0;
	}

	protected boolean is_user_watching = false;
	public void setUserWatching(boolean user_watching) {
		if (is_user_watching == user_watching)
			return;
		is_user_watching = user_watching;
		if (mXMPPConnection != null && mXMPPConnection.isAuthenticated())
			sendUserWatching();
	}

	protected void sendUserWatching() {
		IQ toggle_google_queue = new IQ() {
			public String getChildElementXML() {
				// enable g:q = start queueing packets = do it when the user is gone
				return "<query xmlns='google:queue'><" + (is_user_watching ? "disable" : "enable") + "/></query>";
			}
		};
		toggle_google_queue.setType(IQ.Type.set);
		try {
			mXMPPConnection.sendPacket(toggle_google_queue);
		} catch (NotConnectedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/** Check the server connection, reconnect if needed.
	 *
	 * This function will try to ping the server if we are connected, and try
	 * to reestablish a connection otherwise.
	 */
	public void sendServerPing() {
		if (mXMPPConnection == null || !mXMPPConnection.isAuthenticated()) {
			debugLog("Ping: requested, but not connected to server.");
			requestConnectionState(ConnectionState.ONLINE, false);
			return;
		}
		if (mPingID != null) {
			debugLog("Ping: requested, but still waiting for " + mPingID);
			return; // a ping is still on its way
		}

		// TODO evaluate if PingManager.pingMyServer would be feasible here
		Ping ping = new Ping();
		ping.setType(Type.get);
		ping.setTo(mConfig.server);
		mPingID = ping.getPacketID();
		debugLog("Ping: sending ping " + mPingID);
		try {
			mXMPPConnection.sendPacket(ping);
		} catch (NotConnectedException e) {
			throw new IllegalStateException(e);
		}
		if (mXMPPConnection.isSmEnabled()) {
			debugLog("Ping: sending SM request");
			try {
				mXMPPConnection.requestSmAcknowledgement();
			} catch (StreamManagementNotEnabledException e) {
				throw new IllegalStateException(e);
			} catch (NotConnectedException e) {
				throw new IllegalStateException(e);
			}
		}

		// register ping timeout handler: PACKET_TIMEOUT(30s) + 3s
		registerPongTimeout(PACKET_TIMEOUT + 3000, mPingID);
	}

	private void gotServerPong(String pongID) {
		long latency = System.currentTimeMillis() - mPingTimestamp;
		if (pongID != null && pongID.equals(mPingID))
			Log.i(TAG, String.format("Ping: server latency %1.3fs",
						latency/1000.));
		else
			Log.i(TAG, String.format("Ping: server latency %1.3fs (estimated)",
						latency/1000.));
		mPingID = null;
		mAlarmManager.cancel(mPongTimeoutAlarmPendIntent);
	}

	/** Register a "pong" timeout on the connection. */
	private void registerPongTimeout(long wait_time, String id) {
		mPingID = id;
		mPingTimestamp = System.currentTimeMillis();
		debugLog(String.format("Ping: registering timeout for %s: %1.3fs", id, wait_time/1000.));
		mAlarmManager.set(AlarmManager.RTC_WAKEUP,
				System.currentTimeMillis() + wait_time,
				mPongTimeoutAlarmPendIntent);
	}

	/**
	 * BroadcastReceiver to trigger reconnect on pong timeout.
	 */
	private class PongTimeoutAlarmReceiver extends BroadcastReceiver {
		public void onReceive(Context ctx, Intent i) {
			debugLog("Ping: timeout for " + mPingID);
			onDisconnected("Ping timeout");
		}
	}

	/**
	 * BroadcastReceiver to trigger sending pings to the server
	 */
	private class PingAlarmReceiver extends BroadcastReceiver {
		public void onReceive(Context ctx, Intent i) {
				sendServerPing();
		}
	}

	/**
	 * Registers a smack packet listener for IQ packets, intended to recognize "pongs" with
	 * a packet id matching the last "ping" sent to the server.
	 *
	 * Also sets up the AlarmManager Timer plus necessary intents.
	 */
	private void registerPongListener() {
		// reset ping expectation on new connection
		mPingID = null;

		if (mPongListener != null)
			mXMPPConnection.removePacketListener(mPongListener);

		mPongListener = new PacketListener() {

			@Override
			public void processPacket(Packet packet) {
				if (packet == null) return;

				gotServerPong(packet.getPacketID());
			}

		};

		mXMPPConnection.addPacketListener(mPongListener, new PacketTypeFilter(IQ.class));
		mPingAlarmPendIntent = PendingIntent.getBroadcast(mService.getApplicationContext(), 0, mPingAlarmIntent,
					PendingIntent.FLAG_UPDATE_CURRENT);
		mPongTimeoutAlarmPendIntent = PendingIntent.getBroadcast(mService.getApplicationContext(), 0, mPongTimeoutAlarmIntent,
					PendingIntent.FLAG_UPDATE_CURRENT);
		mAlarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, 
				System.currentTimeMillis() + AlarmManager.INTERVAL_FIFTEEN_MINUTES, AlarmManager.INTERVAL_FIFTEEN_MINUTES, mPingAlarmPendIntent);
	}
	private void unregisterPongListener() {
		mAlarmManager.cancel(mPingAlarmPendIntent);
		mAlarmManager.cancel(mPongTimeoutAlarmPendIntent);
	}

	private void registerMessageListener() {
		// do not register multiple packet listeners
		if (mPacketListener != null)
			mXMPPConnection.removePacketListener(mPacketListener);

		PacketTypeFilter filter = PacketTypeFilter.MESSAGE;

		mPacketListener = new PacketListener() {
			public void processPacket(Packet packet) {
				try {
				if (packet instanceof Message) {
					Message msg = (Message) packet;

					String[] fromJID = getJabberID(msg.getFrom());
					
					int direction = ChatConstants.INCOMING;
					CarbonExtension cc = CarbonExtension.getFrom(msg);

					// check for jabber MUC invitation
					if(msg.getExtension("jabber:x:conference") != null) {
						Log.d(TAG, "handling MUC invitation and aborting futher packet processing...");
						handleMucInvitation(msg);
						return;
					}

					// extract timestamp
					long ts;
					DelayInformation timestamp = DelayInformation.getFrom(msg);
					if (cc != null) // Carbon timestamp overrides packet timestamp
						timestamp = cc.getForwarded().getDelayInfo();
					if (timestamp != null)
						ts = timestamp.getStamp().getTime();
					else
						ts = System.currentTimeMillis();

					// try to extract a carbon
					if (cc != null) {
						Log.d(TAG, "carbon: " + cc.toXML());
						msg = (Message)cc.getForwarded().getForwardedPacket();

						// outgoing carbon: fromJID is actually chat peer's JID
						if (cc.getDirection() == CarbonExtension.Direction.sent) {
							fromJID = getJabberID(msg.getTo());
							direction = ChatConstants.OUTGOING;
						} else {
							fromJID = getJabberID(msg.getFrom());

							// hook off carbonated delivery receipts
							DeliveryReceipt dr = (DeliveryReceipt)msg.getExtension(
									DeliveryReceipt.ELEMENT, DeliveryReceipt.NAMESPACE);
							if (dr != null) {
								Log.d(TAG, "got CC'ed delivery receipt for " + dr.getId());
								changeMessageDeliveryStatus(dr.getId(), ChatConstants.DS_ACKED);
							}
						}
					}

					String chatMessage = msg.getBody();

					// display error inline
					if (msg.getType() == Message.Type.error) {
						if (changeMessageDeliveryStatus(msg.getPacketID(), ChatConstants.DS_FAILED))
							mServiceCallBack.notifyMessage(fromJID, msg.getError().toString(), (cc != null), Message.Type.error);
						return; // we do not want to add errors as "incoming messages"
					}

					// ignore empty messages
					if (chatMessage == null) {
						if (msg.getSubject() != null && msg.getType() == Message.Type.groupchat
								&& multiUserChats.containsKey(fromJID[0])) {
							// this is a MUC subject, update our DB
							ContentValues cvR = new ContentValues();
							cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE, msg.getSubject());
							cvR.put(RosterProvider.RosterConstants.STATUS_MODE, StatusMode.available.ordinal());
							upsertRoster(cvR, fromJID[0]);
							return;
						}
						Log.d(TAG, "empty message.");
						return;
					}

					// carbons are old. all others are new
					int is_new = (cc == null) ? ChatConstants.DS_NEW : ChatConstants.DS_SENT_OR_READ;
					if (msg.getType() == Message.Type.error)
						is_new = ChatConstants.DS_FAILED;


					Log.d(TAG, 
							String.format("attempting to add message '''%s''' from %s to db, msgtype==groupchat?: %b", chatMessage, fromJID[0], msg.getType()==Message.Type.groupchat)
							);
					if(msg.getType() != Message.Type.groupchat
						|| 
						(msg.getType()==Message.Type.groupchat && checkAddMucMessage(msg, msg.getPacketID(), fromJID, ts))
						) {
						Log.d(TAG, "actually adding msg...");
						addChatMessageToDB(direction, fromJID, chatMessage, is_new, ts, msg.getPacketID());
						// prevent if highlighting is enabled and message does not match
						boolean prevent_notify = msg.getType() == Message.Type.groupchat && mConfig.highlightNickMuc &&
								!chatMessage.toLowerCase().contains(multiUserChats.get(fromJID[0]).getNickname().toLowerCase());
						if (direction == ChatConstants.INCOMING && !prevent_notify)
							mServiceCallBack.notifyMessage(fromJID, chatMessage, (cc != null), msg.getType());
						}
					}
				} catch (Exception e) {
					// SMACK silently discards exceptions dropped from processPacket :(
					Log.e(TAG, "failed to process packet:");
					e.printStackTrace();
				}
			}
		};

		mXMPPConnection.addPacketListener(mPacketListener, filter);
	}


	private boolean checkAddMucMessage(Message msg, String packet_id, String[] fromJid, long ts) {
		final String[] projection = new String[] {
				ChatConstants._ID, ChatConstants.MESSAGE,
				ChatConstants.JID, ChatConstants.RESOURCE,
				ChatConstants.PACKET_ID
		};

		//final String content_match= ChatConstants.JID+"='"+fromJid[0]+"' AND "+ChatConstants.MESSAGE+"='"+msg.getBody()+"'"
		//		+" AND "+ChatConstants.DATE+"='"+ts+"'";
		//final String packet_match = ChatConstants.PACKET_ID+"='"+msg.getPacketID()+"'";
		//final String selection = "("+content_match+") OR ("+packet_match+")";
		final String selection = ChatConstants.JID+" = ? AND " + ChatConstants.RESOURCE + " = ? AND (" +
					 ChatConstants.PACKET_ID + " = ? OR " + ChatConstants.DATE + " = ?)";
		final String[] selectionArgs = new String[] { fromJid[0], fromJid[1], packet_id, ""+ts };
		try {
			Cursor cursor = mContentResolver.query(ChatProvider.CONTENT_URI, projection, selection, selectionArgs, null);
			boolean result = (cursor.getCount() == 0);
			cursor.close();
			return result;
		} catch (Exception e) {} // just return true...

		return true;	
	}

	private void registerPresenceListener() {
		// do not register multiple packet listeners
		if (mPresenceListener != null)
			mXMPPConnection.removePacketListener(mPresenceListener);

		mPresenceListener = new PacketListener() {
			public void processPacket(Packet packet) {
				try {
					Presence p = (Presence) packet;
					switch (p.getType()) {
					case subscribe:
						handleIncomingSubscribe(p);
						break;
					case unsubscribe:
						break;
					case unavailable:
						// HACK: better use UserStatusListener
						MUCUser u = MUCUser.getFrom(p);
						String jid[] = p.getFrom().split("/");
						Log.d(TAG, "received presence unavailable: " + u + " jid=" + p.getFrom());
						MultiUserChat muc = multiUserChats.get(jid[0]);
						Log.d(TAG, (muc != null)?muc.getNickname() : "null");
						if (u != null && muc != null && muc.getNickname().equals(jid[1])) {
							// we were kicked! ouch!
							multiUserChats.remove(jid[0]);
							ContentValues cvR = new ContentValues();
							cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE, "Kicked: " + u.getItem().getReason());
							cvR.put(RosterProvider.RosterConstants.STATUS_MODE, StatusMode.offline.ordinal());
							upsertRoster(cvR, jid[0]);
						}
						break;
					}
				} catch (Exception e) {
					// SMACK silently discards exceptions dropped from processPacket :(
					Log.e(TAG, "failed to process presence:");
					e.printStackTrace();
				}
			}
		};

		mXMPPConnection.addPacketListener(mPresenceListener, new PacketTypeFilter(Presence.class));
	}

	private void addChatMessageToDB(int direction, String[] tJID,
			String message, int delivery_status, long ts, String packetID) {
		ContentValues values = new ContentValues();

		values.put(ChatConstants.DIRECTION, direction);
		values.put(ChatConstants.JID, tJID[0]);
		values.put(ChatConstants.RESOURCE, tJID[1]);
		values.put(ChatConstants.MESSAGE, message);
		values.put(ChatConstants.DELIVERY_STATUS, delivery_status);
		values.put(ChatConstants.DATE, ts);
		values.put(ChatConstants.PACKET_ID, packetID);

		mContentResolver.insert(ChatProvider.CONTENT_URI, values);
	}

	private void addChatMessageToDB(int direction, String JID,
			String message, int delivery_status, long ts, String packetID) {
		String[] tJID = {JID, ""};
		addChatMessageToDB(direction, tJID, message, delivery_status, ts, packetID);
	}

	private ContentValues getContentValuesForRosterEntry(final RosterEntry entry) {
		final ContentValues values = new ContentValues();

		values.put(RosterConstants.JID, entry.getUser());
		values.put(RosterConstants.ALIAS, getName(entry));

		Presence presence = mRoster.getPresence(entry.getUser());
		values.put(RosterConstants.STATUS_MODE, getStatusInt(presence));
		if (presence.getType() == Presence.Type.error) {
			values.put(RosterConstants.STATUS_MESSAGE, presence.getError().toString());
		} else
			values.put(RosterConstants.STATUS_MESSAGE, presence.getStatus());
		values.put(RosterConstants.GROUP, getGroup(entry.getGroups()));

		return values;
	}

	private void deleteRosterEntryFromDB(final String jabberID) {
		int count = mContentResolver.delete(RosterProvider.CONTENT_URI,
				RosterConstants.JID + " = ?", new String[] { jabberID });
		debugLog("deleteRosterEntryFromDB: Deleted " + count + " entries");
	}

	private void updateRosterEntryInDB(final RosterEntry entry) {
		upsertRoster(getContentValuesForRosterEntry(entry), entry.getUser());
	}

	private void upsertRoster(final ContentValues values, String jid) {
		if (mContentResolver.update(RosterProvider.CONTENT_URI, values,
				RosterConstants.JID + " = ?", new String[] { jid }) == 0) {
			mContentResolver.insert(RosterProvider.CONTENT_URI, values);
		}
	}

	private String getGroup(Collection<RosterGroup> groups) {
		for (RosterGroup group : groups) {
			return group.getName();
		}
		return "";
	}

	private String getName(RosterEntry rosterEntry) {
		String name = rosterEntry.getName();
		if (name != null && name.length() > 0) {
			return name;
		}
		name = XmppStringUtils.parseLocalpart(rosterEntry.getUser());
		if (name.length() > 0) {
			return name;
		}
		return rosterEntry.getUser();
	}

	private StatusMode getStatus(Presence presence) {
		if (presence.getType() == Presence.Type.subscribe)
			return StatusMode.subscribe;
		if (presence.getType() == Presence.Type.available) {
			if (presence.getMode() != null) {
				return StatusMode.valueOf(presence.getMode().name());
			}
			return StatusMode.available;
		}
		return StatusMode.offline;
	}

	private int getStatusInt(final Presence presence) {
		return getStatus(presence).ordinal();
	}

	private void debugLog(String data) {
		if (LogConstants.LOG_DEBUG) {
			Log.d(TAG, data);
		}
	}

	@Override
	public String getLastError() {
		return mLastError;
    }

	public synchronized void syncDbRooms() {
		if (!isAuthenticated()) {
			debugLog("syncDbRooms: aborting, not yet authenticated");
		}

		java.util.Set<String> joinedRooms = multiUserChats.keySet();
		Cursor cursor = mContentResolver.query(RosterProvider.MUCS_URI, 
				new String[] {RosterProvider.RosterConstants._ID,
					RosterProvider.RosterConstants.JID, 
					RosterProvider.RosterConstants.PASSWORD, 
					RosterProvider.RosterConstants.NICKNAME}, 
				null, null, null);
		final int ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants._ID);
		final int JID_ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants.JID);
		final int PASSWORD_ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants.PASSWORD);
		final int NICKNAME_ID = cursor.getColumnIndexOrThrow(RosterProvider.RosterConstants.NICKNAME);
		
		mucJIDs.clear();
		while(cursor.moveToNext()) {
			int id = cursor.getInt(ID);
			String jid = cursor.getString(JID_ID);
			String password = cursor.getString(PASSWORD_ID);
			String nickname = cursor.getString(NICKNAME_ID);
			mucJIDs.add(jid);
			//debugLog("Found MUC Room: "+jid+" with nick "+nickname+" and pw "+password);
			if(!joinedRooms.contains(jid)) {
				debugLog("room " + jid + " isn't joined yet, i wanna join...");
				joinRoom(jid, nickname, password); // TODO: make historyLen configurable
			}
			//debugLog("found data in contentprovider: "+jid+" "+password+" "+nickname);
		}
		cursor.close();
		
		for(String room : joinedRooms) {
			if(!mucJIDs.contains(room)) {
				quitRoom(room);
			}
		}
	}
	
	protected void handleMucInvitation(Message msg) {
		mServiceCallBack.mucInvitationReceived(
				msg.getFrom(),
				msg.getBody()
				);
	}
	
	private boolean joinRoom(String room, String nickname, String password) {
		MultiUserChat muc = new MultiUserChat(mXMPPConnection, room);
		
		DiscussionHistory history = new DiscussionHistory();
		final String[] projection = new String[] {
				ChatConstants._ID, ChatConstants.DATE,
				ChatConstants.JID, ChatConstants.MESSAGE,
				ChatConstants.PACKET_ID
		};
		final String selection = String.format("%s = '%s'", projection[2], room);
		Cursor cursor = mContentResolver.query(ChatProvider.CONTENT_URI, projection, 
				selection, null, "date DESC LIMIT 1");
		if(cursor.getCount()>0) {
			cursor.moveToFirst();
			long lastDate = cursor.getLong( cursor.getColumnIndexOrThrow(projection[1]) );
			String msg =  cursor.getString( cursor.getColumnIndexOrThrow(projection[3]) );
			Log.d(TAG, String.format("joining room %s i found %d rows of last date %d with msg %s, setting since to %s", room, cursor.getCount(), lastDate, msg, (new Date(lastDate)).toString()) );
			history.setSince( new Date(lastDate) );
		} else Log.d(TAG, "found no old DB messages");
		cursor.close();
		
		ContentValues cvR = new ContentValues();
		cvR.put(RosterProvider.RosterConstants.JID, room);
		cvR.put(RosterProvider.RosterConstants.ALIAS, room);
		cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE, "Synchronizing...");
		cvR.put(RosterProvider.RosterConstants.STATUS_MODE, StatusMode.dnd.ordinal());
		cvR.put(RosterProvider.RosterConstants.GROUP, "MUCs");
		upsertRoster(cvR, room);
		try {
			muc.join(nickname, password, history, 5*PACKET_TIMEOUT);
		} catch (Exception e) {
			Log.e(TAG, "Could not join MUC-room "+room);
			e.printStackTrace();
			cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE, "Error: " + e.getLocalizedMessage());
			cvR.put(RosterProvider.RosterConstants.STATUS_MODE, StatusMode.offline.ordinal());
			upsertRoster(cvR, room);
			if(nickname == null || nickname.equals("")) {
				return joinRoom(room, "NoNick", password);
			}
			return false;
		}

		if(muc.isJoined()) {
			multiUserChats.put(room, muc);
			String roomname = room.split("@")[0];
			try {
				RoomInfo ri = MultiUserChat.getRoomInfo(mXMPPConnection, room);
				// Room Name is called the room description in the wire protocol
				String rn = ri.getDescription();
				if (rn != null && rn.length() > 0)
					roomname = rn;
			} catch (Exception e) {
				// ignore a failed room info request
				Log.d(TAG, "MUC room IQ failed: " + room);
				e.printStackTrace();
			}
			// delay requesting subject until room info IQ returned/failed
			String subject = muc.getSubject();
			cvR.put(RosterProvider.RosterConstants.ALIAS, roomname);
			cvR.put(RosterProvider.RosterConstants.STATUS_MESSAGE, subject);
			cvR.put(RosterProvider.RosterConstants.STATUS_MODE, StatusMode.available.ordinal());
			upsertRoster(cvR, room);
			return true;
		}
		
		return false;
	}

	@Override
	public void sendMucMessage(String room, String message) {
		try {
			multiUserChats.get(room).sendMessage(message);
		} catch (Exception e) {
			Log.e(TAG, "error while sending message to muc room "+room, e);
			e.printStackTrace();
		}
	}

	private void quitRoom(String room) {
		MultiUserChat muc = multiUserChats.get(room); 
		try {
			muc.leave();
		} catch (NotConnectedException e) {
			Log.e(TAG, "error while leaving muc room "+room, e);
		}
		multiUserChats.remove(room);
		mContentResolver.delete(RosterProvider.CONTENT_URI, "jid LIKE ?", new String[] {room});
	}

	@Override
	public boolean inviteToRoom(String contactJid, String roomJid) {
		MultiUserChat muc = multiUserChats.get(roomJid);
		if(contactJid.contains("/")) {
			contactJid = contactJid.split("/")[0];
		}
		Log.d(TAG, "invitng contact: "+contactJid+" to room: "+muc);
		try {
			muc.invite(contactJid, "User "+contactJid+" has invited you to a chat!");
		} catch (NotConnectedException e) {
			Log.e(TAG, "error while sending muc invite", e);
		}
		return false;
	}

	@Override
	public List<ParcelablePresence> getUserList(String jid) {
		MultiUserChat muc = multiUserChats.get(jid);
		if (muc == null) {
			return null;
		}
		List<String> occupants = muc.getOccupants();
		ArrayList<ParcelablePresence> tmpList = new ArrayList<ParcelablePresence>();
		for (String occupant : occupants)
			tmpList.add(new ParcelablePresence(muc.getOccupantPresence(occupant)));
		Collections.sort(tmpList, new Comparator<ParcelablePresence>() {
			@Override
			public int compare(ParcelablePresence lhs, ParcelablePresence rhs) {
				return java.text.Collator.getInstance().compare(lhs.resource, rhs.resource);
			}
		});
		return tmpList;
	}
}
