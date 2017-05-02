package com.dotcms.rest.api.v1.system.websocket;

import com.dotcms.api.system.event.*;
import com.dotcms.concurrent.DotConcurrentFactory;
import com.dotcms.concurrent.DotSubmitter;
import com.dotcms.repackage.com.google.common.annotations.VisibleForTesting;
import com.dotcms.repackage.javax.ws.rs.ForbiddenException;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.UserAPI;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.Logger;
import com.liferay.portal.model.User;
import com.twelvemonkeys.lang.DateUtil;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * This Websocket end-point allows other parts of the system (such as the User
 * Notification component) to register to this service and receive information
 * regarding new notifications or system events. Other application services can
 * get an instance of this end-point via the {@link WebSocketContainerAPI} and
 * send System Events so that other components can read and process them.
 * 
 * @author Jose Castro
 * @version 3.7
 * @since Jul 11, 2016
 *
 */
@SuppressWarnings("serial")
@ServerEndpoint(value = SystemEventsWebSocketEndPoint.API_WS_V1_SYSTEM_EVENTS, encoders = { SystemEventEncoder.class }, configurator = DotCmsWebSocketConfigurator.class)
public class SystemEventsWebSocketEndPoint implements Serializable {

	public static final String ID = "userId";
	public static final String USER = "user";
	public static final String API_WS_V1_SYSTEM_EVENTS = "/api/ws/v1/system/events";


	private final Queue<Session> queue;
	private final UserAPI userAPI;
	private final SystemEventProcessorFactory systemEventProcessorFactory;
    private final PayloadVerifierFactory payloadVerifierFactory;
    private final static ForbiddenCloseCode FORBIDDEN_CLOSE_CODE = new ForbiddenCloseCode();

	/**
	 * Configuration for ping pong strategy
	 */
	public  static final String     WEB_SOCKET_THREAD_POOL_SUBMITTER_NAME = "longpolling";
	public  static final String     DOTCMS_WEBSOCKET_MILLIS_PINGPONG      = "dotcms.websocket.millis.pingpong";
	public  static final String     DOTCMS_WEBSOCKET_USEPINGPONG          = "dotcms.websocket.usepingpong";
	private static final ByteBuffer PING_RECEIVED                         = ByteBuffer.wrap("PING".getBytes());
	private static final ByteBuffer PONG_RECEIVED                         = ByteBuffer.wrap("PONG".getBytes());
	private final boolean      usePingPong;
	private final long         millisForWaitPingPong;
	private final DotSubmitter dotSubmitterPingPong;

	public SystemEventsWebSocketEndPoint() {

		this(new ConcurrentLinkedQueue<Session>(),
				APILocator.getUserAPI(),
                SystemEventProcessorFactory.getInstance(),
                PayloadVerifierFactory.getInstance(),
				DotConcurrentFactory.getInstance());
    }

	@VisibleForTesting
	public SystemEventsWebSocketEndPoint(final Queue<Session> queue,
                                         final UserAPI userAPI,
                                         final SystemEventProcessorFactory systemEventProcessorFactory,
                                         final PayloadVerifierFactory payloadVerifierFactory,
										 final DotConcurrentFactory dotConcurrentFactory) {

		this.queue       = queue;
		this.userAPI     = userAPI;
        this.systemEventProcessorFactory = systemEventProcessorFactory;
        this.payloadVerifierFactory      = payloadVerifierFactory;
        this.usePingPong = Config.getBooleanProperty(DOTCMS_WEBSOCKET_USEPINGPONG, false);
		if (this.usePingPong) {

			this.millisForWaitPingPong = Config.getLongProperty(DOTCMS_WEBSOCKET_MILLIS_PINGPONG,
					DateUtil.MINUTE); // by default 1 min
			this.dotSubmitterPingPong  = dotConcurrentFactory.getSubmitter(WEB_SOCKET_THREAD_POOL_SUBMITTER_NAME);
		} else {
			this.millisForWaitPingPong = -1;
			this.dotSubmitterPingPong  = null;
		}
	}

	@OnOpen
	public void open(final Session session) {

		User user = null;
		boolean isLoggedIn = false;

		if (session.getUserProperties().containsKey(USER)) {

			try {

				user = (User) session.getUserProperties().get(USER);
				this.queue.add(new SessionWrapper(session, user));
				isLoggedIn = true;
			} catch (Exception e) {

				if (Logger.isErrorEnabled(this.getClass())) {

					Logger.error(this.getClass(), e.getMessage(), e);
				}
			}
		}

		if (!isLoggedIn) {

			try {

				final ForbiddenException forbiddenException = new ForbiddenException("A web socket connection requires a previous web session created");
				if (session.isOpen()) {

					session.getAsyncRemote().sendObject(forbiddenException);
					session.close(new CloseReason(FORBIDDEN_CLOSE_CODE ,
							"A web socket connection requires a previous web session created"));
				}
				throw forbiddenException;
			} catch (IOException e) {
				if (Logger.isErrorEnabled(this.getClass())) {

					Logger.error(this.getClass(), e.getMessage(), e);
				}
				throw new IllegalStateException(e);
			}
		} else {
			// if session succesfully we start the ping pong (if it enables)
			this.doPing(session);
		}
	} // open.

	@OnError
	public void error(Session session, Throwable t) {
		queue.remove(session);
	}

	@OnClose
	public void closedConnection(Session session) {
		queue.remove(session);
	}

	@OnMessage
	public void onPong(final PongMessage pongMessage, final Session session) {
		// the browser will send the pong message automatically with the same data we sent on the ping.
		if (PING_RECEIVED.equals(pongMessage.getApplicationData())) {

			this.doPing(session);
		}
	} // onPong.

	private void doPing (final Session session) {
		// wait for N seconds
		if (null != this.dotSubmitterPingPong) {
			this.dotSubmitterPingPong.execute(() -> {

				try {
					// we wait for a N seconds and then send the ping messsage
					if (this.millisForWaitPingPong > 0) {
						Thread.sleep(this.millisForWaitPingPong);
					}

					if (session.isOpen()) {
						Logger.debug(this, "Doing ping to: " + session);
						session.getAsyncRemote().sendPing(PING_RECEIVED);
					} else {

						Logger.debug(this, "Couldn't do the ping to: " + session + ", session is closed");
					}
				} catch (InterruptedException e) {

					Logger.debug(this, e.getMessage(), e);
					Thread.currentThread().interrupt();
				} catch (Exception e) {
					if (Logger.isErrorEnabled(this.getClass())) {

						Logger.error(this.getClass(), e.getMessage(), e);
					}
				}
			});
		}
	} // doPing.


	/**
	 * Sends the specified {@link SystemEvent} object to all the clients
	 * (front-end or back-end services) that are registered to this Websocket
	 * end-point.
	 * 
	 * @param event
	 *            - A new System Event that has been generated.
	 */
	public void sendSystemEvent(final SystemEvent event) {

		final ArrayList<Session> closedSessions = new ArrayList<>();


		try {

			for (Session session : queue) {

				if (!session.isOpen()) {

					closedSessions.add(session);
				} else {

					if (this.apply (event, session)) {

						session.getAsyncRemote().sendObject
								(this.processEvent(session, event));
					} else {

						Logger.debug(this, "The event: " + event
								+ ", has been filtered for the session: " + session.getId());
					}
				}
			}

			this.queue.removeAll(closedSessions);
		} catch (Throwable e) {

			Logger.error(this, "An error occurred when sending a message through the " + this.getClass().getName(), e);
		}
	} // sendSystemEvent.

	private SystemEvent processEvent(final Session session,
									 final SystemEvent event) {

		final SystemEventProcessor processor =
				this.systemEventProcessorFactory.createProcessor(event.getEventType());

		return null != processor? processor.process(event,
													(null != session && session instanceof SessionWrapper)?
															SessionWrapper.class.cast(session).getUser():null)
				 			      : event;
	} // processEvent.

    /**
     * Verifies if the current user has the "visibility" rights to use this given payload
     *
     * @param session Session wrapper needed in order to obtain the current user information
     * @param payload Payload to validate
     * @return true the current user has "visibility" rights on this payload
     */
    private boolean validPayload(final SessionWrapper session,
                                 final Payload payload) {

        //Get the verifier associated to this Payload
        final PayloadVerifier verifier = this.payloadVerifierFactory.getVerifier(payload);

        //Check if we have the "visibility" rights to use this payload
        return (null != verifier) ? verifier.verified(payload, session.getUser()) : true;
    }

	private boolean apply(final SystemEvent event,
						  final Session session) throws DotDataException  {

		final Payload payload = event.getPayload();
		boolean apply = true; // by default consider it as Visibility.GLOBAL

		if (null != payload) {

			if (null != payload.getVisibility()) {

				if (session instanceof SessionWrapper) {

					if (null != SessionWrapper.class.cast(session).getUser()) {
                        apply = this.validPayload((SessionWrapper) session, payload);
                    }
                }
            }
        } else {

			apply = false; // if the payload is null, must not send to the session.
		}

		return apply;
	} // apply.



} // E:O:F:SystemEventsWebSocketEndPoint.
