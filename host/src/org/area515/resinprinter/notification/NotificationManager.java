package org.area515.resinprinter.notification;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Future;

import javax.websocket.server.ServerContainer;

import org.area515.resinprinter.display.InappropriateDeviceException;
import org.area515.resinprinter.job.PrintJob;
import org.area515.resinprinter.printer.Printer;
import org.area515.resinprinter.server.HostProperties;
import org.area515.resinprinter.server.Main;
import org.area515.resinprinter.slice.StlError;

public class NotificationManager {
	private static List<Notifier> notifiers = null;
	
	public static void start(ServerContainer container) {
		if (notifiers != null) {
			return;
		}
		
		notifiers = new ArrayList<Notifier>();
		List<Class<Notifier>> notifyierClasses = HostProperties.Instance().getNotifiers();
		for (Class<Notifier> currentClass : notifyierClasses) {
			Notifier notifier;
			try {
				notifier = currentClass.newInstance();
				notifier.register(container);
				notifiers.add(notifier);
			} catch (InstantiationException | IllegalAccessException | InappropriateDeviceException e) {
				System.out.println("Couldn't start Notifier");
				e.printStackTrace();
			}
		}
	}
	
	public static Future<?> jobChanged(final Printer printer, final PrintJob job) {
		return Main.GLOBAL_EXECUTOR.submit(new Runnable() {
			@Override
			public void run() {
				for (Notifier currentNotifier : notifiers) {
					currentNotifier.jobChanged(printer, job);
				}
			}
		});
	}
	
	public static void errorEncountered(PrintJob job, List<StlError> errors) {
		for (Notifier currentNotifier : notifiers) {
			currentNotifier.geometryError(job, errors);
		}
	}
	
	public static Future<?> printerChanged(final Printer printer) {
		return Main.GLOBAL_EXECUTOR.submit(new Runnable() {
			@Override
			public void run() {
				for (Notifier currentNotifier : notifiers) {
					currentNotifier.printerChanged(printer);
				}
			}
		});
	}
	
	public static void shutdown() {
		for (Notifier currentNotifier : notifiers) {
			currentNotifier.stop();
		}
	}	
	
	public static void sendPingMessage(String message) {
		for (Notifier currentNotifier : notifiers) {
			currentNotifier.sendPingMessage(message);
		}
	}
	
	public static void hostSettingsChanged() {
		for (Notifier currentNotifier : notifiers) {
			currentNotifier.hostSettingsChanged();
		}
	}	
	
	public static void fileUploadComplete(File fileUploaded) {
		for (Notifier currentNotifier : notifiers) {
			currentNotifier.fileUploadComplete(fileUploaded);
		}
	}
	
	public static Long getTimeOfLastClientPing() {
		Long latestPing = null;
		for (Notifier currentNotifier : notifiers) {
			Long currentPing = currentNotifier.getTimeOfLastClientPing();
			if (currentPing != null && (latestPing == null || currentPing > latestPing)) {
				latestPing = currentPing;
			}
		}
		
		return latestPing;
	}
}
