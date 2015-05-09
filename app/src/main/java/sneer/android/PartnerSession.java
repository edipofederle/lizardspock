package sneer.android;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Messenger;
import android.widget.Toast;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import sneer.android.impl.Envelope;
import sneer.android.impl.IPCProtocol;

import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_IMPORTANT;
import static android.os.Message.obtain;
import static sneer.android.impl.Envelope.envelope;
import static sneer.android.impl.IPCProtocol.ENVELOPE;

public class PartnerSession implements Closeable {

	public static PartnerSession join(Activity activity, Listener listener) {
		return new PartnerSession(activity, listener);
	}

	public boolean wasStartedByMe() {
		throw new RuntimeException("Not implemented yet");
	}

	public void send(Object payload) {
		await(connectionPending);
		sendToSneer(payload);
	}

	public interface Listener {
		void onUpToDate();
		void onMessage(Message message);
    }


	@Override
	public void close() {
		activity.unbindService(connection);
	}


	private final Activity activity;
    private final Listener listener;
	private final CountDownLatch connectionPending = new CountDownLatch(1);
	private final ServiceConnection connection = createConnection();
	private Messenger toSneer;


	private ServiceConnection createConnection() {
		return new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName name, IBinder service) {
				toSneer = new Messenger(service);
				Messenger callback = new Messenger(new FromSneerHandler());
				sendToSneer(callback);
				connectionPending.countDown();
			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				toSneer = null;
				finish("Connection to Sneer was lost");
				connectionPending.countDown();
			}
		};
	}


    private PartnerSession(Activity activity, Listener listener) {
		this.activity = activity;
        this.listener = listener;
		boolean success = activity.bindService(
			activity.getIntent().<Intent>getParcelableExtra(IPCProtocol.JOIN_SESSION),
			connection,
			BIND_AUTO_CREATE | BIND_IMPORTANT
		);
	    if (!success)
		    finish("Unable to connect to Sneer");
    }


	private void finish(String endingToast) {
		toast(endingToast);
		activity.finish();
	}


	private void sendToSneer(Object data) {
		android.os.Message msg = asMessage(data);
		try {
			doSendToSneer(msg);
		} catch (Exception e) {
			handleException(e);
		}
	}


	private android.os.Message asMessage(Object data) {
		android.os.Message ret = obtain();
		Bundle bundle = new Bundle();
		bundle.putParcelable(ENVELOPE, envelope(data));
		ret.setData(bundle);
		return ret;
	}


	private void doSendToSneer(android.os.Message msg) throws Exception {
		if (toSneer == null) {
			toast("No connection to Sneer");
			return;
		}
		toSneer.send(msg);
	}


	private class FromSneerHandler extends Handler { @Override public void handleMessage(android.os.Message msg) {
		Bundle data = msg.getData();
		data.setClassLoader(Envelope.class.getClassLoader());
		Object content = ((Envelope) data.getParcelable(ENVELOPE)).content;
		if (content.equals(IPCProtocol.UP_TO_DATE))
			activity.runOnUiThread(new Runnable() { @Override public void run() {
				listener.onUpToDate();
			}});
		else
			listener.onMessage(getMessage(content));
	}}


	private Message getMessage(Object content) {
		final Map<String, Object> map = (Map<String, Object>)content;
		return new Message() {
			@Override
			public boolean wasSentByMe() {
				return (Boolean)map.get(IPCProtocol.WAS_SENT_BY_ME);
			}

			@Override
			public Object payload() {
				return map.get(IPCProtocol.PAYLOAD);
			}
		};
	}


	private void handleException(Exception e) {
		e.printStackTrace();
		toast(e.getMessage());
	}


	private void toast(String message) {
		Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
	}


	private static void await (CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) { throw new RuntimeException(e); }
	}

}
