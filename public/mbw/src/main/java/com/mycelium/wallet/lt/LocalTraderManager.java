package com.mycelium.wallet.lt;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.common.base.Preconditions;
import com.mrd.bitlib.crypto.InMemoryPrivateKey;
import com.mrd.bitlib.crypto.PublicKey;
import com.mrd.bitlib.model.Address;
import com.mycelium.lt.ApiUtils;
import com.mycelium.lt.ChatMessageEncryptionKey;
import com.mycelium.lt.api.LtApi;
import com.mycelium.lt.api.LtApiException;
import com.mycelium.lt.api.model.GpsLocation;
import com.mycelium.lt.api.model.LtSession;
import com.mycelium.lt.api.model.TradeSession;
import com.mycelium.lt.api.model.TraderInfo;
import com.mycelium.lt.api.params.LoginParameters;
import com.mycelium.wallet.AndroidRandomSource;
import com.mycelium.wallet.Constants;
import com.mycelium.wallet.MbwManager;
import com.mycelium.wallet.Record;
import com.mycelium.wallet.RecordManager;
import com.mycelium.wallet.lt.api.CreateInstantBuyOrder;
import com.mycelium.wallet.lt.api.CreateSellOrder;
import com.mycelium.wallet.lt.api.Request;
import com.mycelium.wallet.persistence.TradeSessionDb;

public class LocalTraderManager {

   public static final String GCM_SENDER_ID = "1025080855849";

   private static final String TAG = "LocalTraderManager";

   final private Context _context;
   final private RecordManager _recordManager;
   final private TradeSessionDb _db;
   final private LtApi _api;
   final private MbwManager _mbwManager;
   final private Set<LocalTraderEventSubscriber> _subscribers;
   final private Thread _executer;
   private LtSession _session;
   final private List<Request> _requests;
   private boolean _isLoggedIn;
   private Address _localTraderAddress;
   private long _lastTraderSynchronization;
   private long _lastTraderNotification;
   private GpsLocation _currentLocation;
   private String _nickname;
   private boolean _isLocalTraderDisabled;
   private boolean _playSoundOnTradeNotification;
   private boolean _useMiles;
   private TraderChangeMonitor _traderChangeMonitor;
   private TradeSessionChangeMonitor _tradeSessionChangeMonitor;
   private boolean _notificationsEnabled;
   private TraderInfo _cachedTraderInfo;
   private long _lastNotificationSoundTimestamp;

   public LocalTraderManager(Context context, RecordManager recordManager, TradeSessionDb db, LtApi api,
         MbwManager mbwManager) {
      _notificationsEnabled = true;
      _context = context;
      _recordManager = recordManager;
      _db = db;
      _api = api;
      _mbwManager = mbwManager;
      _subscribers = new HashSet<LocalTraderEventSubscriber>();
      _requests = new LinkedList<Request>();

      // Preferences
      SharedPreferences preferences = _context.getSharedPreferences(Constants.LOCAL_TRADER_SETTINGS_NAME,
            Activity.MODE_PRIVATE);

      _nickname = preferences.getString(Constants.LOCAL_TRADER_NICKNAME_SETTING, null);
      String addressString = preferences.getString(Constants.LOCAL_TRADER_ADDRESS_SETTING, null);
      if (addressString != null) {
         _localTraderAddress = Address.fromString(addressString, _mbwManager.getNetwork());
         // May be null
      }

      // Load location from preferences or use default
      // _currentLocation = new
      // GpsLocation(Constants.LOCAL_TRADER_DEFAULT_LOCATION.latitude,
      // (float) Constants.LOCAL_TRADER_DEFAULT_LOCATION.longitude,
      // Constants.LOCAL_TRADER_DEFAULT_LOCATION.name);

      _currentLocation = new GpsLocation(preferences.getFloat(Constants.LOCAL_TRADER_LATITUDE_SETTING,
            (float) Constants.LOCAL_TRADER_DEFAULT_LOCATION.latitude), preferences.getFloat(
            Constants.LOCAL_TRADER_LONGITUDE_SETTING, (float) Constants.LOCAL_TRADER_DEFAULT_LOCATION.longitude),
            preferences.getString(Constants.LOCAL_TRADER_LOCATION_NAME_SETTING,
                  Constants.LOCAL_TRADER_DEFAULT_LOCATION.name));

      _isLocalTraderDisabled = preferences.getBoolean(Constants.LOCAL_TRADER_DISABLED_SETTING, false);
      _playSoundOnTradeNotification = preferences.getBoolean(
            Constants.LOCAL_TRADER_PLAY_SOUND_ON_TRADE_NOTIFICATION_SETTING, true);
      _useMiles = preferences.getBoolean(Constants.LOCAL_TRADER_USE_MILES_SETTING, false);
      _lastTraderSynchronization = preferences.getLong(Constants.LOCAL_TRADER_LAST_TRADER_SYNCHRONIZATION_SETTING, 0);
      _lastTraderNotification = preferences.getLong(Constants.LOCAL_TRADER_LAST_TRADER_NOTIFICATION_SETTING, 0);

      _executer = new Thread(new Executor());
      _executer.setDaemon(true);
      _executer.start();

      _traderChangeMonitor = new TraderChangeMonitor(this, _api);
      _tradeSessionChangeMonitor = new TradeSessionChangeMonitor(this, _api);
   }

   public void subscribe(LocalTraderEventSubscriber listener) {
      synchronized (_subscribers) {
         _subscribers.add(listener);
         if (_subscribers.size() > 5) {
            Log.w("LocalTraderManager", "subscriber size seems large: " + _subscribers.size());
         }
      }
   }

   public void unsubscribe(LocalTraderEventSubscriber listener) {
      synchronized (_subscribers) {
         boolean removed = _subscribers.remove(listener);
         if (!removed) {
            Log.e("LocalTraderManager", "SUBSCRIBER NOT REMOVED");
         }
      }
   }

   public void makeRequest(Request request) {
      if (request.requiresLogin() && !hasLocalTraderAccount()) {
         throw new RuntimeException("Cannot make login request when trading is disabled");
      }
      synchronized (_requests) {
         _requests.add(request);
         _requests.notify();
      }
   }

   public void startMonitoringTrader() {
      _traderChangeMonitor.startMonitoring();
   }

   public void stopMonitoringTrader() {
      _traderChangeMonitor.stopMonitoring();
   }

   public void startMonitoringTradeSession(TradeSessionChangeMonitor.Listener listener) {
      if (_session == null) {
         Log.e(TAG, "Trying to monitor trade session without having a session");
         return;
      }
      _tradeSessionChangeMonitor.startMonitoring(_session.id, listener);
   }

   public void stopMonitoringTradeSession() {
      _tradeSessionChangeMonitor.stopMonitoring();
   }

   public void enableNotifications(boolean enabled) {
      _notificationsEnabled = enabled;
   }

   public boolean areNotificationsEnabled() {
      return _notificationsEnabled;
   }

   public interface LocalManagerApiContext {
      public void handleErrors(Request request, int errorCode);

      public void updateLocalTradeSessions(Collection<TradeSession> collection);

      public void updateSingleTradeSession(TradeSession tradeSession);

      public void cacheTraderInfo(TraderInfo traderInfo);
   }

   private class Executor implements Runnable, LocalManagerApiContext {

      @Override
      public void run() {
         while (true) {

            // Grab a request or wait
            Request request;
            synchronized (_requests) {
               if (_requests.size() == 0) {
                  try {
                     _requests.wait();
                  } catch (InterruptedException e) {
                     break;
                  }
               }
               request = _requests.remove(0);
            }

            // If the request requires a session and we don't got one, get one
            if (request.requiresSession() && _session == null) {
               if (!renewSession()) {
                  continue;
               }
            }

            // If the request requires a login and we don't are not logged in,
            // login
            if (request.requiresLogin() && !_isLoggedIn) {
               if (!login()) {
                  continue;
               }
            }
            request.execute(this, _api, _session.id, _subscribers);
         }

      }

      private boolean renewSession() {
         try {
            // Get new session
            _session = _api.createSession(LtApi.VERSION, _mbwManager.getLanguage(),
                  _mbwManager.getBitcoinDenomination().getAsciiName()).getResult();
            _isLoggedIn = false;
            return true;
         } catch (LtApiException e) {
            // Handle errors
            handleErrors(null, e.errorCode);
            return false;
         }
      }

      private boolean login() {
         Preconditions.checkNotNull(_session.id);
         // Sign session ID with private key
         InMemoryPrivateKey privateKey = getLocalTraderPrivateKey();
         if (privateKey == null) {
            handleErrors(null, LtApi.ERROR_CODE_TRADER_DOES_NOT_EXIST);
            return false;
         }
         String sigHashSessionId = ApiUtils.generateUuidHashSignature(privateKey, _session.id,
               new AndroidRandomSource());
         try {
            // Login
            LoginParameters params = new LoginParameters(getLocalTraderAddress(), sigHashSessionId);
            params.setGcmId(getGcmRegistrationId());
            _api.traderLogin(_session.id, params).getResult();
            _isLoggedIn = true;
            return true;
         } catch (LtApiException e) {
            if (e.errorCode == LtApi.ERROR_CODE_INVALID_SESSION) {
               if (renewSession()) {
                  return login();
               } else {
                  return false;
               }
            } else {
               handleErrors(null, e.errorCode);
               return false;
            }
         }
      }

      public void updateLocalTradeSessions(Collection<TradeSession> collection) {
         LocalTraderManager.this.updateLocalTradeSessions(collection);
      }

      public void updateSingleTradeSession(TradeSession tradeSession) {
         LocalTraderManager.this.updateSingleTradeSession(tradeSession);
      }

      @Override
      public void cacheTraderInfo(TraderInfo traderInfo) {
         LocalTraderManager.this.cacheTraderInfo(traderInfo);
      }

      public void handleErrors(Request request, int errorCode) {
         switch (errorCode) {
         case LtApi.ERROR_CODE_INVALID_SESSION:
            if (renewSession()) {
               if (login()) {
                  synchronized (_requests) {
                     _requests.add(request);
                     _requests.notify();
                  }
               }
            }
            break;
         case LtApi.ERROR_CODE_NO_SERVER_CONNECTION:
            notifyNoConnection(errorCode);
            break;
         case LtApi.ERROR_CODE_INCOMPATIBLE_API_VERSION:
            notifyIncompatibleApiVersion(errorCode);
            break;
         case LtApi.ERROR_CODE_TRADER_DOES_NOT_EXIST:
            _isLoggedIn = false;
            _session = null;
            // Disconnect trader account
            unsetLocalTraderAccount();
            notifyNoTraderAccount(errorCode);
            break;
         default:
            _isLoggedIn = false;
            _session = null;
            notifyError(errorCode);
            break;
         }
      }

   }

   private void notifyNoConnection(final int errorCode) {
      synchronized (_subscribers) {
         for (final LocalTraderEventSubscriber s : _subscribers) {
            s.getHandler().post(new Runnable() {

               @Override
               public void run() {
                  if (!s.onNoLtConnection()) {
                     s.onLtError(errorCode);
                  }
               }
            });
         }
      }
   }

   private void notifyIncompatibleApiVersion(final int errorCode) {
      synchronized (_subscribers) {
         for (final LocalTraderEventSubscriber s : _subscribers) {
            s.getHandler().post(new Runnable() {

               @Override
               public void run() {
                  if (!s.onLtNoIncompatibleVersion()) {
                     s.onLtError(errorCode);
                  }
               }
            });
         }
      }
   }

   private void notifyNoTraderAccount(final int errorCode) {
      synchronized (_subscribers) {
         for (final LocalTraderEventSubscriber s : _subscribers) {
            s.getHandler().post(new Runnable() {

               @Override
               public void run() {
                  if (!s.onLtNoTraderAccount()) {
                     s.onLtError(errorCode);
                  }
               }
            });
         }
      }
   }

   private void notifyError(final int errorCode) {
      synchronized (_subscribers) {
         for (final LocalTraderEventSubscriber s : _subscribers) {
            s.getHandler().post(new Runnable() {

               @Override
               public void run() {
                  s.onLtError(errorCode);
               }
            });
         }
      }
   }

   private void notifyTraderActivity(final long timestamp) {
      synchronized (_subscribers) {
         for (final LocalTraderEventSubscriber s : _subscribers) {
            s.getHandler().post(new Runnable() {

               @Override
               public void run() {
                  s.onLtTraderActicityNotification(timestamp);
               }
            });
         }
      }
   }

   /**
    * May return null
    */
   public synchronized TradeSession getLocalTradeSession(UUID tradeSessionId) {
      return _db.get(tradeSessionId);
   }

   public synchronized Collection<TradeSession> getLocalTradeSessions() {
      return _db.getAll();
   }

   public synchronized Collection<TradeSession> getLocalBuyTradeSessions() {
      return _db.getBuyTradeSessions();
   }

   public synchronized Collection<TradeSession> getLocalSellTradeSessions() {
      return _db.getSellTradeSessions();
   }

   public synchronized int countLocalTradeSessions() {
      return _db.countTradeSessions();
   }

   public synchronized int countLocalBuyTradeSessions() {
      return _db.countBuyTradeSessions();
   }

   public synchronized int countLocalSellTradeSessions() {
      return _db.countSellTradeSessions();
   }

   public synchronized boolean isViewed(TradeSession tradeSession) {
      return _db.getViewTimeById(tradeSession.id) >= tradeSession.lastChange;
   }

   public synchronized void markViewed(TradeSession tradeSession) {
      _db.markViewed(tradeSession);
   }

   private synchronized void updateLocalTradeSessions(Collection<TradeSession> remoteList) {
      // Get all the local sessions
      Collection<TradeSession> localList = _db.getAll();

      // Iterate over local items to find records to delete or update locally
      Iterator<TradeSession> localIt = localList.iterator();
      while (localIt.hasNext()) {
         TradeSession localItem = localIt.next();
         TradeSession remoteItem = findAndEliminate(localItem, remoteList);
         if (remoteItem == null) {
            // A local item is not in the remote list, remove it locally
            _db.delete(localItem.id);
         } else {
            // A local item is in the new list, see if it needs to be updated
            if (needsUpdate(localItem, remoteItem)) {
               _db.update(remoteItem);
            }
         }
      }

      // Iterate over remaining remote items and insert them
      Iterator<TradeSession> remoteIt = remoteList.iterator();
      while (remoteIt.hasNext()) {
         TradeSession remoteItem = remoteIt.next();
         _db.insert(remoteItem);
      }

   }

   private synchronized void updateSingleTradeSession(TradeSession item) {
      _db.insert(item);
   }

   public void cacheTraderInfo(TraderInfo traderInfo) {
      _cachedTraderInfo = traderInfo;
   }

   public TraderInfo getCachedTraderInfo() {
      return _cachedTraderInfo;
   }

   private TradeSession findAndEliminate(TradeSession item, Collection<TradeSession> list) {
      Iterator<TradeSession> it = list.iterator();
      while (it.hasNext()) {
         TradeSession t = it.next();
         if (t.equals(item)) {
            it.remove();
            return t;
         }
      }
      return null;
   }

   private boolean needsUpdate(TradeSession oldValue, TradeSession newValue) {
      Preconditions.checkArgument(oldValue.id.equals(newValue.id));
      return oldValue.lastChange < newValue.lastChange;
   }

   private SharedPreferences.Editor getEditor() {
      return _context.getSharedPreferences(Constants.LOCAL_TRADER_SETTINGS_NAME, Activity.MODE_PRIVATE).edit();
   }

   public boolean hasLocalTraderAccount() {
      return getLocalTraderPrivateKey() != null;
   }

   public String getNickname() {
      return _nickname;
   }

   public Address getLocalTraderAddress() {
      return _localTraderAddress;
   }

   private InMemoryPrivateKey getLocalTraderPrivateKey() {
      Record record = _recordManager.getRecord(_localTraderAddress);
      if (record != null && record.hasPrivateKey()) {
         return record.key;
      }
      if (_localTraderAddress != null) {
         unsetLocalTraderAccount();
      }
      return null;
   }

   public ChatMessageEncryptionKey generateChatMessageEncryptionKey(PublicKey foreignPublicKey, UUID tradeSessionId) {
      return ChatMessageEncryptionKey.fromEcdh(foreignPublicKey, getLocalTraderPrivateKey(), tradeSessionId);
   }

   public void unsetLocalTraderAccount() {
      _session = null;
      _localTraderAddress = null;
      _nickname = null;
      SharedPreferences.Editor editor = getEditor();
      editor.remove(Constants.LOCAL_TRADER_ADDRESS_SETTING);
      editor.remove(Constants.LOCAL_TRADER_NICKNAME_SETTING);
      setLastTraderSynchronization(0);
      _db.deleteAll();
      editor.commit();
   }

   public void setLocalTraderData(Address address, String nickname) {
      _session = null;
      _localTraderAddress = Preconditions.checkNotNull(address);
      _nickname = Preconditions.checkNotNull(nickname);
      SharedPreferences.Editor editor = getEditor();
      editor.putString(Constants.LOCAL_TRADER_ADDRESS_SETTING, address.toString());
      editor.putString(Constants.LOCAL_TRADER_NICKNAME_SETTING, nickname);
      editor.commit();
   }

   public synchronized void setLastTraderSynchronization(long timestamp) {
      _lastTraderSynchronization = timestamp;
      SharedPreferences.Editor editor = getEditor();
      editor.putLong(Constants.LOCAL_TRADER_LAST_TRADER_SYNCHRONIZATION_SETTING, timestamp);
      editor.commit();
   }

   public synchronized long getLastTraderSynchronization() {
      return _lastTraderSynchronization;
   }

   public synchronized boolean setLastTraderNotification(long timestamp) {
      if (timestamp <= _lastTraderNotification) {
         return false;
      }
      _lastTraderNotification = timestamp;
      SharedPreferences.Editor editor = getEditor();
      editor.putLong(Constants.LOCAL_TRADER_LAST_TRADER_NOTIFICATION_SETTING, timestamp);
      editor.commit();
      Log.i(TAG, "Updated trader notification timestamp to: " + timestamp);
      if (needsTraderSynchronization()) {
         notifyTraderActivity(_lastTraderNotification);
      }
      return true;
   }

   /**
    * Has the Local Trader server reported that it has more recent trader data
    * than what the app has seen?
    */
   public synchronized boolean needsTraderSynchronization() {
      return _lastTraderSynchronization < _lastTraderNotification;
   }

   public void setLocation(GpsLocation location) {
      SharedPreferences.Editor editor = getEditor();
      _currentLocation = location;
      editor.putFloat(Constants.LOCAL_TRADER_LATITUDE_SETTING, (float) location.latitude);
      editor.putFloat(Constants.LOCAL_TRADER_LONGITUDE_SETTING, (float) location.longitude);
      editor.putString(Constants.LOCAL_TRADER_LOCATION_NAME_SETTING, location.name);
      editor.commit();
   }

   public GpsLocation getUserLocation() {
      return _currentLocation;
   }

   public void setLocalTraderDisabled(boolean disabled) {
      SharedPreferences.Editor editor = getEditor();
      _isLocalTraderDisabled = disabled;
      editor.putBoolean(Constants.LOCAL_TRADER_DISABLED_SETTING, disabled);
      editor.commit();
   }

   public boolean isLocalTraderDisabled() {
      return _isLocalTraderDisabled;
   }

   public void setPlaySoundOnTradeNotification(boolean enabled) {
      SharedPreferences.Editor editor = getEditor();
      _playSoundOnTradeNotification = enabled;
      editor.putBoolean(Constants.LOCAL_TRADER_PLAY_SOUND_ON_TRADE_NOTIFICATION_SETTING, enabled);
      editor.commit();
   }

   public boolean getPlaySoundOnTradeNotification() {
      return _playSoundOnTradeNotification;
   }

   public void setLastNotificationSoundTimestamp(long timestamp) {
      if (timestamp > _lastNotificationSoundTimestamp) {
         _lastNotificationSoundTimestamp = timestamp;
      }
   }

   public long getLastNotificationSoundTimestamp() {
      return _lastNotificationSoundTimestamp;
   }

   public void setUseMiles(boolean enabled) {
      SharedPreferences.Editor editor = getEditor();
      _useMiles = enabled;
      editor.putBoolean(Constants.LOCAL_TRADER_USE_MILES_SETTING, enabled);
      editor.commit();
   }

   public boolean useMiles() {
      return _useMiles;
   }

   public boolean isCaptchaRequired(Request request) {
      if (request instanceof CreateSellOrder) {
         return _session == null ? true : _session.captcha.contains(LtSession.CaptchaCommands.CREATE_SELL_ORDER);
      } else if (request instanceof CreateInstantBuyOrder) {
         return _session == null ? true : _session.captcha.contains(LtSession.CaptchaCommands.CREATE_INSTANT_BUY_ORDER);
      }
      return false;
   }

   public void initializeGooglePlayServices() {
      if (GooglePlayServicesUtil.isGooglePlayServicesAvailable(_context) != ConnectionResult.SUCCESS) {
         return;
      }
      if (getGcmRegistrationId() == null) {
         // Get the GCM ID in a background thread
         new Thread(new Runnable() {
            @Override
            public void run() {
               GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(_context);
               try {
                  String regId = gcm.register(LocalTraderManager.GCM_SENDER_ID);
                  storeGcmRegistrationId(regId);
               } catch (IOException e) {
                  Log.w(TAG, "IO exception while getting GCM ID:" + e.getMessage());
               }
            }
         }).start();
      }
   }

   private synchronized String getGcmRegistrationId() {
      final SharedPreferences prefs = getGcmPreferences();
      String registrationId = prefs.getString("gcmid", null);
      if (registrationId == null) {
         Log.i(TAG, "GCM registration not found.");
         return null;
      }
      // Check if app was updated; if so, it must clear the registration ID
      // since the existing regID is not guaranteed to work with the new
      // app version.
      int registeredVersion = prefs.getInt("appVersion", Integer.MIN_VALUE);
      int currentVersion = getAppVersion();
      if (registeredVersion != currentVersion) {
         Log.i(TAG, "App version changed.");
         return null;
      }
      return registrationId;
   }

   /**
    * Stores the registration ID and app versionCode in the application's
    * {@code SharedPreferences}.
    * 
    * @param context
    *           application's context.
    * @param regId
    *           registration ID
    */
   private synchronized void storeGcmRegistrationId(String regId) {
      final SharedPreferences prefs = getGcmPreferences();
      int appVersion = getAppVersion();
      Log.i(TAG, "Saving regId on app version " + appVersion);
      SharedPreferences.Editor editor = prefs.edit();
      editor.putString("gcmid", regId);
      editor.putInt("appVersion", appVersion);
      editor.commit();
   }

   /**
    * @return Application's version code from the {@code PackageManager}.
    */
   private int getAppVersion() {
      try {
         PackageInfo packageInfo = _context.getPackageManager().getPackageInfo(_context.getPackageName(), 0);
         return packageInfo.versionCode;
      } catch (NameNotFoundException e) {
         // should never happen
         throw new RuntimeException("Could not get package name: " + e);
      }
   }

   private SharedPreferences getGcmPreferences() {
      return _context.getSharedPreferences(Constants.LOCAL_TRADER_GCM_SETTINGS_NAME, Activity.MODE_PRIVATE);
   }

}
