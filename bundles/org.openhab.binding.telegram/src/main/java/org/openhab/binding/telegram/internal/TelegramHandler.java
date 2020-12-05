/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.telegram.internal;

import static org.openhab.binding.telegram.internal.TelegramBindingConstants.*;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.telegram.internal.action.TelegramActions;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.TelegramException;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.CallbackQuery;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.PhotoSize;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.response.BaseResponse;

import okhttp3.OkHttpClient;

/**
 * The {@link TelegramHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Jens Runge - Initial contribution
 * @author Alexander Krasnogolowy - using Telegram library from pengrad
 * @author Jan N. Klug - handle file attachments
 * @author Michael Murton - add trigger channel
 */
@NonNullByDefault
public class TelegramHandler extends BaseThingHandler {

    @NonNullByDefault
    private class ReplyKey {

        final Long chatId;
        final String replyId;

        public ReplyKey(Long chatId, String replyId) {
            this.chatId = chatId;
            this.replyId = replyId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(chatId, replyId);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ReplyKey other = (ReplyKey) obj;
            return Objects.equals(chatId, other.chatId) && Objects.equals(replyId, other.replyId);
        }
    }

    private static Gson gson = new Gson();
    private static JsonParser json = new JsonParser();

    private final List<Long> authorizedSenderChatId = new ArrayList<>();
    private final List<Long> receiverChatId = new ArrayList<>();

    private final Logger logger = LoggerFactory.getLogger(TelegramHandler.class);
    private @Nullable ScheduledFuture<?> thingOnlineStatusJob;

    // Keep track of the callback id created by Telegram. This must be sent back in
    // the answerCallbackQuery
    // to stop the progress bar in the Telegram client
    private final Map<ReplyKey, String> replyIdToCallbackId = new HashMap<>();
    // Keep track of message id sent with reply markup because we want to remove the
    // markup after the user provided an
    // answer and need the id of the original message
    private final Map<ReplyKey, Integer> replyIdToMessageId = new HashMap<>();

    private @Nullable TelegramBot bot;
    private @Nullable OkHttpClient botLibClient;
    private @Nullable HttpClient downloadDataClient;
    private @Nullable ParseMode parseMode;

    public TelegramHandler(Thing thing, @Nullable HttpClient httpClient) {
        super(thing);
        downloadDataClient = httpClient;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // no commands to handle
    }

    @Override
    public void initialize() {
        TelegramConfiguration config = getConfigAs(TelegramConfiguration.class);

        String botToken = config.getBotToken();

        List<String> chatIds = config.getChatIds();
        if (chatIds != null) {
            createReceiverChatIdsAndAuthorizedSenderChatIds(chatIds);
        }
        String parseModeAsString = config.getParseMode();
        if (!parseModeAsString.isEmpty()) {
            try {
                parseMode = ParseMode.valueOf(parseModeAsString);
            } catch (IllegalArgumentException e) {
                logger.warn("parseMode is invalid and will be ignored. Only Markdown or HTML are allowed values");
            }
        }

        OkHttpClient.Builder prepareConnection = new OkHttpClient.Builder().connectTimeout(75, TimeUnit.SECONDS)
                .readTimeout(75, TimeUnit.SECONDS);

        String proxyHost = config.getProxyHost();
        Integer proxyPort = config.getProxyPort();
        String proxyType = config.getProxyType();

        if (proxyHost != null && proxyPort != null) {
            InetSocketAddress proxyAddr = new InetSocketAddress(proxyHost, proxyPort);

            Proxy.Type proxyTypeParam = Proxy.Type.SOCKS;

            if ("HTTP".equals(proxyType)) {
                proxyTypeParam = Proxy.Type.HTTP;
            }

            Proxy proxy = new Proxy(proxyTypeParam, proxyAddr);

            logger.debug("{} Proxy {}:{} is used for telegram ", proxyTypeParam, proxyHost, proxyPort);
            prepareConnection.proxy(proxy);
        }

        botLibClient = prepareConnection.build();
        updateStatus(ThingStatus.UNKNOWN);
        delayThingOnlineStatus();
        TelegramBot localBot = bot = new TelegramBot.Builder(botToken).okHttpClient(botLibClient).build();
        localBot.setUpdatesListener(this::handleUpdates, this::handleExceptions,
                getGetUpdatesRequest(config.getLongPollingTime()));
    }

    private void createReceiverChatIdsAndAuthorizedSenderChatIds(List<String> chatIds) {
        authorizedSenderChatId.clear();
        receiverChatId.clear();

        for (String chatIdStr : chatIds) {
            String trimmedChatId = chatIdStr.trim();
            try {
                if (trimmedChatId.startsWith("<")) {
                    // inbound only
                    authorizedSenderChatId.add(Long.valueOf(trimmedChatId.substring(1)));
                } else if (trimmedChatId.startsWith(">")) {
                    // outbound only
                    receiverChatId.add(Long.valueOf(trimmedChatId.substring(1)));
                } else {
                    // bi-directional (default)
                    Long chatId = Long.valueOf(trimmedChatId);
                    authorizedSenderChatId.add(chatId);
                    receiverChatId.add(chatId);
                }
            } catch (NumberFormatException e) {
                logger.warn("The chat id {} is not a number and will be ignored", chatIdStr);
            }
        }
    }

    private GetUpdates getGetUpdatesRequest(int longPollingTime) {
        return new GetUpdates().timeout(longPollingTime * 1000);
    }

    private void handleExceptions(@Nullable TelegramException exception) {
        final TelegramBot localBot = bot;
        if (exception != null) {
            if (exception.response() != null) {
                BaseResponse localResponse = exception.response();
                if (localResponse.errorCode() == 401) { // unauthorized
                    cancelThingOnlineStatusJob();
                    if (localBot != null) {
                        localBot.removeGetUpdatesListener();
                    }
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "Unauthorized attempt to connect to the Telegram server, please check if the bot token is valid");
                    return;
                }
            }
            if (exception.getCause() != null) { // cause is only non-null in case of an IOException
                cancelThingOnlineStatusJob();
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, exception.getMessage());
                delayThingOnlineStatus();
                return;
            }
            logger.warn("Telegram exception: {}", exception.getMessage());
        }
    }

    private String getFullDownloadUrl(String fileId) {
        final TelegramBot bot = this.bot;
        if (bot == null) {
            return "";
        }
        return bot.getFullFilePath(bot.execute(new GetFile(fileId)).file());
    }

    private int handleUpdates(List<Update> updates) {
        final TelegramBot localBot = bot;
        if (localBot == null) {
            logger.warn("Cannot process updates if no telegram bot is present.");
            return UpdatesListener.CONFIRMED_UPDATES_NONE;
        }

        cancelThingOnlineStatusJob();
        updateStatus(ThingStatus.ONLINE);
        for (Update update : updates) {
            String lastMessageText = null;
            Integer lastMessageDate = null;
            String lastMessageFirstName = null;
            String lastMessageLastName = null;
            String lastMessageUsername = null;
            String lastMessageURL = null;
            Long chatId = null;
            String replyId = null;

            Message message = update.message();
            CallbackQuery callbackQuery = update.callbackQuery();

            if (message != null) {
                chatId = message.chat().id();
                if (!authorizedSenderChatId.contains(chatId)) {
                    logger.warn(
                            "Ignored message from unknown chat id {}. If you know the sender of that chat, add it to the list of chat ids in the thing configuration to authorize it",
                            chatId);
                    continue; // this is very important regarding security to avoid commands from an unknown
                    // chat
                }

                // build and publish messageEvent trigger channel payload
                JsonObject messageRaw = json.parse(gson.toJson(message)).getAsJsonObject();
                JsonObject messagePayload = new JsonObject();
                messagePayload.addProperty("message_id", message.messageId());
                messagePayload.addProperty("from",
                        String.join(" ", new String[] { message.from().firstName(), message.from().lastName() }));
                messagePayload.addProperty("chat_id", message.chat().id());
                if (messageRaw.has("text")) {
                    messagePayload.addProperty("text", message.text());
                }
                if (messageRaw.has("animation")) {
                    JsonObject animationPayload = messageRaw.getAsJsonObject("animation");
                    String animationURL = getFullDownloadUrl(
                            animationPayload.getAsJsonPrimitive("file_id").getAsString());
                    animationPayload.addProperty("file_url", animationURL);
                    messagePayload.addProperty("animation_url", animationURL);
                    if (animationPayload.has("thumb")) {
                        animationPayload.getAsJsonObject("thumb").addProperty("file_url", getFullDownloadUrl(
                                animationPayload.getAsJsonObject("thumb").getAsJsonPrimitive("file_id").getAsString()));
                    }
                }
                if (messageRaw.has("audio")) {
                    JsonObject audioPayload = messageRaw.getAsJsonObject("audio");
                    String audioURL = getFullDownloadUrl(audioPayload.getAsJsonPrimitive("file_id").getAsString());
                    audioPayload.addProperty("file_url", audioURL);
                    messagePayload.addProperty("audio_url", audioURL);
                    if (audioPayload.has("thumb")) {
                        audioPayload.getAsJsonObject("thumb").addProperty("file_url", getFullDownloadUrl(
                                audioPayload.getAsJsonObject("thumb").getAsJsonPrimitive("file_id").getAsString()));
                    }
                }
                if (messageRaw.has("document")) {
                    JsonObject documentPayload = messageRaw.getAsJsonObject("document");
                    String documentURL = getFullDownloadUrl(
                            documentPayload.getAsJsonPrimitive("file_id").getAsString());
                    documentPayload.addProperty("file_url", documentURL);
                    messagePayload.addProperty("document_url", documentURL);
                    if (documentPayload.has("thumb")) {
                        documentPayload.getAsJsonObject("thumb").addProperty("file_url", getFullDownloadUrl(
                                documentPayload.getAsJsonObject("thumb").getAsJsonPrimitive("file_id").getAsString()));
                    }
                }
                if (messageRaw.has("photo")) {
                    JsonArray photoURLArray = new JsonArray();
                    for (JsonElement photoPayload : messageRaw.getAsJsonArray("photo")) {
                        JsonObject photoPayloadObject = photoPayload.getAsJsonObject();
                        String photoURL = getFullDownloadUrl(
                                photoPayloadObject.getAsJsonPrimitive("file_id").getAsString());
                        photoPayloadObject.addProperty("file_url", photoURL);
                        photoURLArray.add(photoURL);
                    }
                    messagePayload.add("photo_url", photoURLArray);
                }
                if (messageRaw.has("sticker")) {
                    JsonObject stickerPayload = messageRaw.getAsJsonObject("sticker");
                    String stickerURL = getFullDownloadUrl(stickerPayload.getAsJsonPrimitive("file_id").getAsString());
                    stickerPayload.addProperty("file_url", stickerURL);
                    messagePayload.addProperty("sticker_url", stickerURL);
                    if (stickerPayload.has("thumb")) {
                        stickerPayload.getAsJsonObject("thumb").addProperty("file_url", getFullDownloadUrl(
                                stickerPayload.getAsJsonObject("thumb").getAsJsonPrimitive("file_id").getAsString()));
                    }
                }
                if (messageRaw.has("video")) {
                    JsonObject videoPayload = messageRaw.getAsJsonObject("video");
                    String videoURL = getFullDownloadUrl(videoPayload.getAsJsonPrimitive("file_id").getAsString());
                    videoPayload.addProperty("file_url", videoURL);
                    messagePayload.addProperty("video_url", videoURL);
                    if (videoPayload.has("thumb")) {
                        videoPayload.getAsJsonObject("thumb").addProperty("file_url", getFullDownloadUrl(
                                videoPayload.getAsJsonObject("thumb").getAsJsonPrimitive("file_id").getAsString()));
                    }
                }
                if (messageRaw.has("video_note")) {
                    JsonObject videoNotePayload = messageRaw.getAsJsonObject("animation");
                    String videoNoteURL = getFullDownloadUrl(
                            videoNotePayload.getAsJsonPrimitive("file_id").getAsString());
                    videoNotePayload.addProperty("file_url", videoNoteURL);
                    messagePayload.addProperty("video_note_url", videoNoteURL);
                    if (videoNotePayload.has("thumb")) {
                        videoNotePayload.getAsJsonObject("thumb").addProperty("file_url", getFullDownloadUrl(
                                videoNotePayload.getAsJsonObject("thumb").getAsJsonPrimitive("file_id").getAsString()));
                    }
                }
                if (messageRaw.has("voice")) {
                    JsonObject voicePayload = messageRaw.getAsJsonObject("voice");
                    String voiceURL = getFullDownloadUrl(voicePayload.getAsJsonPrimitive("file_id").getAsString());
                    voicePayload.addProperty("file_url", voiceURL);
                    messagePayload.addProperty("voice_url", voiceURL);
                }
                triggerEvent(MESSAGEEVENT, messagePayload.toString());
                triggerEvent(MESSAGERAWEVENT, messageRaw.toString());

                // process content
                if (message.audio() != null) {
                    lastMessageURL = getFullDownloadUrl(message.audio().fileId());
                } else if (message.document() != null) {
                    lastMessageURL = getFullDownloadUrl(message.document().fileId());
                } else if (message.photo() != null) {
                    PhotoSize[] photoSizes = message.photo();
                    logger.trace("Received photos {}", Arrays.asList(photoSizes));
                    Arrays.sort(photoSizes, Comparator.comparingInt(PhotoSize::fileSize).reversed());
                    lastMessageURL = getFullDownloadUrl(photoSizes[0].fileId());
                } else if (message.text() != null) {
                    lastMessageText = message.text();
                } else if (message.video() != null) {
                    lastMessageURL = getFullDownloadUrl(message.video().fileId());
                } else if (message.voice() != null) {
                    lastMessageURL = getFullDownloadUrl(message.voice().fileId());
                } else {
                    logger.debug("Received message with unsupported content: {}", message);
                    continue;
                }

                // process metadata
                if (lastMessageURL != null || lastMessageText != null) {
                    lastMessageDate = message.date();
                    lastMessageFirstName = message.from().firstName();
                    lastMessageLastName = message.from().lastName();
                    lastMessageUsername = message.from().username();
                }
            } else if (callbackQuery != null && callbackQuery.message() != null
                    && callbackQuery.message().text() != null) {
                String[] callbackData = callbackQuery.data().split(" ", 2);

                if (callbackData.length == 2) {
                    replyId = callbackData[0];
                    lastMessageText = callbackData[1];
                    lastMessageDate = callbackQuery.message().date();
                    lastMessageFirstName = callbackQuery.from().firstName();
                    lastMessageLastName = callbackQuery.from().lastName();
                    lastMessageUsername = callbackQuery.from().username();
                    chatId = callbackQuery.message().chat().id();
                    replyIdToCallbackId.put(new ReplyKey(chatId, replyId), callbackQuery.id());

                    // build and publish callbackEvent trigger channel payload
                    JsonObject callbackRaw = json.parse(gson.toJson(callbackQuery)).getAsJsonObject();
                    JsonObject callbackPayload = new JsonObject();
                    callbackPayload.addProperty("message_id", callbackQuery.message().messageId());
                    callbackPayload.addProperty("from", String.join(" ",
                            new String[] { callbackQuery.from().firstName(), callbackQuery.from().lastName() }));
                    callbackPayload.addProperty("chat_id", callbackQuery.message().chat().id());
                    callbackPayload.addProperty("callback_id", callbackQuery.id());
                    callbackPayload.addProperty("reply_id", callbackQuery.data().split(" ", 2)[0]);
                    callbackPayload.addProperty("text", callbackQuery.data().split(" ", 2)[1]);
                    triggerEvent(CALLBACKEVENT, callbackPayload.toString());
                    triggerEvent(CALLBACKRAWEVENT, callbackRaw.toString());

                    logger.debug("Received callbackId {} for chatId {} and replyId {}", callbackQuery.id(), chatId,
                            replyId);
                } else {
                    logger.warn("The received callback query {} has not the right format (must be seperated by spaces)",
                            callbackQuery.data());
                }
            }
            updateChannel(LASTMESSAGETEXT, lastMessageText != null ? new StringType(lastMessageText) : UnDefType.NULL);
            updateChannel(LASTMESSAGEURL, lastMessageURL != null ? new StringType(lastMessageURL) : UnDefType.NULL);
            updateChannel(LASTMESSAGEDATE, lastMessageDate != null
                    ? new DateTimeType(
                            ZonedDateTime.ofInstant(Instant.ofEpochSecond(lastMessageDate.intValue()), ZoneOffset.UTC))
                    : UnDefType.NULL);
            updateChannel(LASTMESSAGENAME, (lastMessageFirstName != null || lastMessageLastName != null)
                    ? new StringType((lastMessageFirstName != null ? lastMessageFirstName + " " : "")
                            + (lastMessageLastName != null ? lastMessageLastName : ""))
                    : UnDefType.NULL);
            updateChannel(LASTMESSAGEUSERNAME,
                    lastMessageUsername != null ? new StringType(lastMessageUsername) : UnDefType.NULL);
            updateChannel(CHATID, chatId != null ? new StringType(chatId.toString()) : UnDefType.NULL);
            updateChannel(REPLYID, replyId != null ? new StringType(replyId) : UnDefType.NULL);
        }
        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private synchronized void delayThingOnlineStatus() {
        thingOnlineStatusJob = scheduler.schedule(() -> {
            // if no error was returned within 10s, we assume the initialization went well
            updateStatus(ThingStatus.ONLINE);
        }, 10, TimeUnit.SECONDS);
    }

    private synchronized void cancelThingOnlineStatusJob() {
        final ScheduledFuture<?> thingOnlineStatusJob = this.thingOnlineStatusJob;
        if (thingOnlineStatusJob != null) {
            thingOnlineStatusJob.cancel(true);
            this.thingOnlineStatusJob = null;
        }
    }

    @Override
    public void dispose() {
        logger.debug("Trying to dispose Telegram client");
        cancelThingOnlineStatusJob();
        OkHttpClient localClient = botLibClient;
        TelegramBot localBot = bot;
        if (localClient != null && localBot != null) {
            localBot.removeGetUpdatesListener();
            localClient.dispatcher().executorService().shutdown();
            localClient.connectionPool().evictAll();
            logger.debug("Telegram client closed");
        }
        super.dispose();
    }

    public void updateChannel(String channelName, State state) {
        updateState(new ChannelUID(getThing().getUID(), channelName), state);
    }

    public void triggerEvent(String channelName, String payload) {
        triggerChannel(new ChannelUID(getThing().getUID(), channelName), payload);
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(TelegramActions.class);
    }

    /**
     * get the list of all authorized senders
     *
     * @return list of chatIds
     */
    public List<Long> getAuthorizedSenderChatIds() {
        return authorizedSenderChatId;
    }

    /**
     * get the list of all receivers
     *
     * @return list of chatIds
     */
    public List<Long> getReceiverChatIds() {
        return receiverChatId;
    }

    public void addMessageId(Long chatId, String replyId, Integer messageId) {
        replyIdToMessageId.put(new ReplyKey(chatId, replyId), messageId);
    }

    @Nullable
    public String getCallbackId(Long chatId, String replyId) {
        return replyIdToCallbackId.get(new ReplyKey(chatId, replyId));
    }

    public @Nullable Integer removeMessageId(Long chatId, String replyId) {
        return replyIdToMessageId.remove(new ReplyKey(chatId, replyId));
    }

    @Nullable
    public ParseMode getParseMode() {
        return parseMode;
    }

    @SuppressWarnings("rawtypes")
    @Nullable
    public <T extends BaseRequest, R extends BaseResponse> R execute(BaseRequest<T, R> request) {
        TelegramBot localBot = bot;
        return localBot != null ? localBot.execute(request) : null;
    }

    @Nullable
    public HttpClient getClient() {
        return downloadDataClient;
    }
}
