import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.glassfish.grizzly.utils.Pair;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.polls.SendPoll;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendSticker;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.polls.Poll;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Quizbot extends TelegramLongPollingBot {
    private final DB database = new DB();
    private final Map<Long, Connection>connections = new HashMap<>();
    private final Map<Long, Pair<Integer, Long>>stats = new HashMap<>();
    private final Map<Long, String>state = new HashMap<>();
    private final Map<String, Message>storage = new HashMap<>();
    private final Map<Long, List<Question>>quiz = new HashMap<>();
    private final List<Item>subjects = new ArrayList<>();
    private final Map<Long, String>language = new HashMap<>();
    private final Map<Long, List<Message>>quizHistory = new HashMap<>();
    private final Map<Long, String>lastSelected = new HashMap<>();

    @Override
    public String getBotUsername() {
        return "quizent_bot";
    }

    @Override
    public String getBotToken() {
        return "5070995738:AAEfYVhJDBnc2Qa14TlwsUUYR9z2tFAz3oE";
    }

    @Override
    public void onUpdateReceived(Update update){
        // System.out.println(update);
        if (update.hasMessage()) {
            if (update.getMessage().hasDocument() && state.get(update.getMessage().getChatId()).contains("waiting4")){
                String selectedSubject = state.get(update.getMessage().getChatId());
                selectedSubject = selectedSubject.substring(1 + selectedSubject.indexOf('4'));
                Long chat_id = update.getMessage().getChatId();
                String fileId = update.getMessage().getDocument().getFileId();
                GetFile getFile = new GetFile(fileId);
                try {
                    String filePath = execute(getFile).getFilePath();
                    File path = downloadFile(filePath);
                    String absPath = path.getAbsolutePath();
                    String newFilename = absPath.substring(absPath.lastIndexOf('/')+1, absPath.indexOf('.')) + ".docx";
                    String finalPath = absPath.substring(0, absPath.lastIndexOf('/')+1) + newFilename;
                    Files.move(Path.of(path.getAbsolutePath()), Path.of(finalPath));
                    Path msWordPath = Paths.get(finalPath);
                    XWPFDocument document = new XWPFDocument(Files.newInputStream(msWordPath));
                    List<XWPFParagraph> paragraphs = document.getParagraphs();
                    List<Question>questions = new ArrayList<>();
                    List<String>options = new ArrayList<>();
                    for (XWPFParagraph paragraph : paragraphs){
                        if (paragraph.getRuns().get(0).isBold()){
                            if (!options.isEmpty()) {
                                questions.get(questions.size() - 1).setOptions(options);
                                options.clear();
                            }
                            questions.add(new Question(paragraph.getParagraphText(), 1));
                        } else {
                            options.add(paragraph.getParagraphText());
                        }
                    }
                    questions.get(questions.size() - 1).setOptions(options);
                    options.clear();
                    document.close();
                    Files.delete(Path.of(finalPath));
                    Statement statement = connections.get(chat_id).createStatement();
                    ResultSet resultSet = statement.executeQuery(String.format("select id from subjects where name = '%s'",selectedSubject));
                    int subject_id = 0, uid = 0;
                    if (resultSet.next())
                        subject_id = resultSet.getInt(1);
                    ResultSet resultSet2 = statement.executeQuery(String.format("select id from users where username = '%s' or user_id = %d", update.getMessage().getFrom().getUserName(), update.getMessage().getFrom().getId()));
                    if (resultSet2.next())
                        uid = resultSet2.getInt(1);
                    while(!questions.isEmpty()){
                        Question question = questions.get(0);
                        ResultSet rs = statement.executeQuery(String.format("insert into questions(text, subject_id, uid) values ('%s', %d, %d) on conflict do nothing returning id", question.getQuestion(), subject_id, uid));
                        if (rs.next()) {
                            int qid = rs.getInt(1);
                            options = question.getOptions();
                            for (String option : options)
                                statement.execute("insert into options(text, qid, correct) values ('"+option+"', "+qid+", " + (options.indexOf(option) == 0) + ")");
                        } else
                            System.out.println("skipped the duplicate question");
                        questions.remove(0);
                    }
                } catch (TelegramApiException | IOException | SQLException e) {
                    e.printStackTrace();
                }
                state.put(update.getMessage().getChatId(), "");
                sendMessage(update.getMessage().getChatId(), String.format(translate("Тест по предмету <b>%s</b> добавлен ✅", language.get(chat_id)), selectedSubject));
            }
            Message message = update.getMessage();
            if (message.hasText()) {
                if (message.getText().equals("/start")) {
                        System.out.println("User started the bot");
                        subjects.clear();
                        state.remove(message.getChatId());
                        quiz.remove(message.getChatId());
                        storage.remove(message.getChatId().toString());
                        stats.remove(message.getChatId());
                        connections.remove(message.getChatId());
                        Connection connection = database.connect();
                        connections.put(message.getChatId(), connection);
                        try {
                            PreparedStatement preparedStatement = connection.prepareStatement("select paid, admin, lang from users where user_id = ? or username = ?");
                            preparedStatement.setLong(1, message.getChatId());
                            preparedStatement.setString(2, message.getFrom().getUserName());
                            ResultSet records = preparedStatement.executeQuery();
                            if (records.next() && records.getBoolean("paid")) {
                                Statement statement = connection.createStatement();
                                language.put(message.getChatId(), records.getString("lang"));
                                ResultSet subs = statement.executeQuery(String.format("select name from subjects where lang = '%s'", language.get(message.getChatId())));
                                while (subs.next()) {
                                    String subject = subs.getString("name");
                                    if (subjects.stream().noneMatch(element -> element.getCaption().equals(subject)))
                                        subjects.add(new Item(subject));
                                }
                                if (records.getBoolean("admin"))
                                    if (subjects.stream().noneMatch(element -> element.getCaption().equals("Добавить предмет")))
                                        subjects.add(new Item(translate("Добавить предмет", language.get(message.getChatId())), "add2+subject"));
                                sendMessage(message.getChatId(), translate("\uD83D\uDCDA Выберите предмет:", language.get(message.getChatId())), buildMarkup(subjects, 1));
                            } else {
                                sendMessage(message.getChatId(), translate("Пожалуйста, свяжитесь с @meirbnb чтобы приобрести подписку.", language.get(message.getChatId())));
                                Statement statement = connection.createStatement();
                                Timestamp timestamp = new Timestamp(new Date().getTime());
                                statement.execute(String.format("insert into users(user_id, username, paid, admin, date) values (%d, '%s', false, false, '%s') on conflict do nothing", message.getFrom().getId(), message.getFrom().getUserName(), timestamp));
                            }
                        } catch (SQLException ex) {
                            System.out.println(ex.getMessage());
                        }
                    } else if (message.getText().equals("/lang")){
                        List<Item>langs = new ArrayList<>();
                        langs.add(new Item("\uD83C\uDDF0\uD83C\uDDFF Қазақша", "changeLang+kaz"));
                        langs.add(new Item("\uD83C\uDDF7\uD83C\uDDFA Русский", "changeLang+rus"));
                        langs.add(new Item("\uD83C\uDDFA\uD83C\uDDF8 English", "changeLang+eng"));
                        sendMessage(message.getChatId(), translate("Выберите язык бота:", language.get(message.getChatId())), buildMarkup(langs, 1));
                    } else if (message.getText().equals("/statistics")){
                        Connection connection;
                        if (connections.containsKey(message.getChatId()))
                            connection = connections.get(message.getChatId());
                        else {
                            connection = database.connect();
                            connections.put(message.getChatId(), connection);
                        }
                        try {
                            int uid;
                            Statement statement = connection.createStatement();
                            ResultSet userInfo = statement.executeQuery(String.format("select id from users where user_id = %d or username = '%s'", message.getFrom().getId(), message.getFrom().getUserName()));
                            if (userInfo.next()) {
                                uid = userInfo.getInt(1);
                                ResultSet statistics = statement.executeQuery("select * from stats where uid = " + uid);
                                StringBuilder text = new StringBuilder();
                                text.append(translate("<b>\uD83D\uDCCA Статистика</b>\n\n", language.get(message.getChatId())));
                                while(statistics.next()){
                                    ResultSet subjects = connection.createStatement().executeQuery("select name from subjects where id = " + statistics.getInt("sid"));
                                    if (subjects.next()){
                                        text.append(translate("<b>\uD83D\uDCD4 Предмет:</b> ", language.get(message.getChatId()))).append(subjects.getString(1)).append("\n\n");
                                        text.append(translate("<b>\uD83D\uDCCC Всего:</b> ", language.get(message.getChatId()))).append(statistics.getInt("points")).append(translate(" баллов\n\n", language.get(message.getChatId())));
                                        text.append(translate("<b>\uD83D\uDCDC Количество пройденных тестов:</b> ", language.get(message.getChatId()))).append(statistics.getString("no")).append("\n\n");
                                        Date date = new SimpleDateFormat("yyyy-MM-dd").parse(statistics.getString("date"));
                                        String data = DateFormat.getDateInstance(SimpleDateFormat.LONG, new Locale("ru")).format(date);
                                        text.append(translate("<b>\uD83D\uDCC5 Дата последнего тестирование: </b>", language.get(message.getChatId()))).append(data).append("\n\n\n\n");
                                    }
                                }
                                if (text.length() > 22)
                                    sendMessage(message.getChatId(), text.toString());
                                else
                                    sendMessage(message.getChatId(), translate("Статистика не доступна!\nСперва попробуйте пройти тестирование.", language.get(message.getChatId())));
                            } else {
                                sendMessage(message.getChatId(), translate("Статистика не доступна!\nСперва попробуйте пройти тестирование.", language.get(message.getChatId())));
                            }
                        } catch (SQLException | ParseException e) {
                            e.printStackTrace();
                        }
                    } else if (message.getText().equals("/cancel")){
                        subjects.clear();
                        state.remove(message.getChatId());
                        quiz.remove(message.getChatId());
                        storage.remove(message.getChatId().toString());
                        //stats.remove(message.getChatId());
                        connections.remove(message.getChatId());
                        System.out.println("User unsubscribed from the bot");
                        sendSticker(message.getChatId(), "CAACAgIAAxkBAAEDpiVh2ulL2CYBw2z6BwOMJhHDRZLG7QAChAADwZxgDIJNRx1SD6TZIwQ");
                        Connection connection;
                        if (connections.containsKey(message.getChatId()))
                            connection = connections.get(message.getChatId());
                        else {
                            connection = database.connect();
                            connections.put(message.getChatId(), connection);
                        }
                        try {
                            Statement statement = connection.createStatement();
                            statement.execute(String.format("update users set paid = false where username = '%s' or user_id = %d", message.getFrom().getUserName(), message.getFrom().getId()));
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    } else {
                        if (!state.isEmpty() && state.get(message.getChatId()).equals("waiting4subject")) {
                            if (subjects.stream().noneMatch(element -> element.getCaption().equals(message.getText()))) {
                                Item addSubjectBtn = subjects.stream().filter(element -> element.getCaption().equals(translate("Добавить предмет", language.get(message.getChatId())))).findFirst().get();
                                subjects.add(subjects.indexOf(addSubjectBtn), new Item(message.getText()));
                                Connection connection = connections.get(message.getChatId());
                                int uid = 0;
                                try {
                                    Statement statement = connection.createStatement();
                                    ResultSet rs = statement.executeQuery(String.format("select id from users where username = '%s' or user_id = %d", message.getFrom().getUserName(), message.getFrom().getId()));
                                    if (rs.next())
                                        uid = rs.getInt(1);
                                    Timestamp timestamp = new Timestamp(new Date().getTime());
                                    statement.execute("insert into subjects(name, uid, added_date, lang) values ('" + message.getText() + "', " + uid + ", '" + timestamp + "', '" + language.get(message.getChatId()) + "') on conflict do nothing");
                                } catch (SQLException e) {
                                    e.printStackTrace();
                                }
                            }
                            sendMessage(message.getChatId(), String.format(translate("Предмет <b>%s</b> добавлен ✅", language.get(message.getChatId())), message.getText()));
                            sendMessage(message.getChatId(), translate("\uD83D\uDCDA Выберите предмет:", language.get(message.getChatId())), buildMarkup(subjects, 1));
                            state.put(message.getChatId(), "");
                        } else if (!state.isEmpty() && state.get(message.getChatId()).contains("waiting4fix")){
                            String query = state.get(message.getChatId());
                            int qid = Integer.parseInt(query.substring(1 + query.indexOf('+')));
                            String correction = message.getText();
                            String fixtype = query.substring(query.indexOf('x')+1, query.indexOf('+'));
                            Connection connection;
                            if (connections.containsKey(message.getChatId())) {
                                connection = connections.get(message.getChatId());
                            }
                            else {
                                connection = database.connect();
                                connections.put(message.getChatId(), connection);
                            }
                            try {
                                Statement statement = connection.createStatement();
                                ResultSet rs = statement.executeQuery(String.format("select id from users where username = '%s' or user_id = %d", message.getFrom().getUserName(), message.getFrom().getId()));
                                if (rs.next()) {
                                    Timestamp today = new Timestamp(new Date().getTime());
                                    statement.execute(String.format("insert into reports(qid, fix, new_value, uid, added_date) values (%d, '%s', '%s', %d, '%s')", qid, fixtype, correction, rs.getInt(1), today));
                                }
                            } catch (SQLException e) {
                                e.printStackTrace();
                            } finally {
                                sendMessage(message.getChatId(), translate("Ваш запрос принят и будет рассмотрен модераторами ✅", language.get(message.getChatId())));
                                state.put(update.getMessage().getChatId(), "");
                            }
                        }
                    }
                }
            } else if (update.hasPoll()) {
            Poll poll = update.getPoll();
            String question = poll.getQuestion();
            String correct = poll.getOptions().get(poll.getCorrectOptionId()).getText();
            String selected = poll.getOptions().stream().filter(option -> option.getVoterCount() == 1).findFirst().get().getText();
            Message message = storage.get(poll.getId());
            long chat_id = message.getChatId();
            if (selected.equals(correct))
                    stats.put(chat_id, new Pair<>(stats.get(chat_id).getFirst() + 1, stats.get(chat_id).getSecond()));
            int id = Integer.parseInt(question.substring(0, question.indexOf(")")));
            if (quiz.get(chat_id) == null)
                return;
            if (quiz.get(chat_id).isEmpty()) {
                long elapsed = (System.currentTimeMillis() - stats.get(chat_id).getSecond()) / 1000;
                int percentage = stats.get(chat_id).getFirst();
                if (percentage == 15)
                    sendSticker(chat_id, "CAACAgIAAxkBAAEDpi1h2umfYElqefRxM_T2vEtA-eiX2AACewADwZxgDNsaH7YdVDaIIwQ");
                else if (percentage > 5)
                    sendSticker(chat_id, "CAACAgIAAxkBAAEDphhh2uiHq3uiWGb1R4ups6KOJmexAgACgAADwZxgDDUiPX15tvOeIwQ");
                else
                    sendSticker(chat_id, "CAACAgIAAxkBAAEDphph2ukLSkTxjsk3o4_Rb3LjB2YHzQACfgADwZxgDAsUf929Iv3zIwQ");
                String text = translate("<b>\uD83D\uDCCA Результат: </b>", language.get(chat_id)) + percentage + translate(" из ", language.get(chat_id)) + id + " (" + (percentage * 100) / id + "%)";
                text += translate("\n<b>⏱️ Затраченное время:</b> ", language.get(chat_id)) + elapsed / 60 + translate(" мин. ", language.get(chat_id)) + elapsed % 60 + translate(" сек.", language.get(chat_id));
                Timestamp timestamp = new Timestamp(new Date().getTime());
                sendMessage(chat_id, text);
                Connection connection;
                if (connections.containsKey(chat_id))
                    connection = connections.get(chat_id);
                else {
                    connection = database.connect();
                    connections.put(chat_id, connection);
                }
                try {
                    Statement statement = connection.createStatement();
                    int uid = 0, sid = 0;
                    ResultSet rs = statement.executeQuery(String.format("select id from users where username = '%s' and user_id = %d", message.getChat().getUserName(), message.getChat().getId()));
                    if (rs.next())
                        uid = rs.getInt(1);
                    ResultSet rs2 = statement.executeQuery(String.format("select id from subjects where name = '%s'", lastSelected.get(chat_id)));
                    if (rs2.next())
                        sid = rs2.getInt(1);
                    ResultSet rs3 = statement.executeQuery(String.format("select * from stats where uid = %d and sid = %d", uid, sid));
                    if (rs3.next()){
                        // String today   = timestamp.toLocalDateTime().toLocalDate().toString();
                        // String lastday = rs3.getDate("date").toLocalDate().toString();
                        //if (!lastday.equals(today))
                        int currentPoints = rs3.getInt("points");
                        int currentNo = rs3.getInt("no");
                        statement.execute(String.format("update stats set points = %d, date = '%s', no = %d where uid = %d and sid = %d", currentPoints + percentage, timestamp, currentNo + 1, uid, sid));
                    } else statement.execute(String.format("insert into stats(uid, points, date, sid, no) values (%d, %d, '%s', %d, %d)", uid, percentage, timestamp, sid, 1));
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                stats.remove(chat_id);
                for (Message current : quizHistory.get(chat_id))
                    deleteMessage(current.getChatId(), current.getMessageId());
                quizHistory.remove(chat_id);
            }
            else {
                Question question2 = quiz.get(chat_id).get(0);
                quiz.get(chat_id).remove(question2);
                sendPoll(chat_id, 1 + id + ") " + question2.getQuestion(), question2.getOptions(), question2.getCorrectId());
            }
        } else if (update.hasCallbackQuery()){
            CallbackQuery callbackQuery = update.getCallbackQuery();
            String queryData = callbackQuery.getData();
            String selectedSubject = queryData;
            Long chat_id = callbackQuery.getMessage().getChatId();
            if (queryData.contains("+")) {
                selectedSubject = queryData.substring(1 + queryData.indexOf("+"));
                queryData = queryData.substring(0, queryData.indexOf("+"));
                if (queryData.equals("test")){
                        if (subjects.isEmpty())
                            return;
                        List<Question> test = LoadTest(callbackQuery.getFrom().getId(), selectedSubject, language.get(chat_id));
                        if (test.isEmpty()){
                            editMessage(chat_id, callbackQuery.getMessage().getMessageId(), String.format(translate("Тесты по предмету <b>%s</b> не найдены.\nОбратитесь к своему преподавателю", language.get(chat_id)), selectedSubject), null);
                            return;
                        }
                        quiz.put(chat_id, test);
                        Question current = quiz.get(chat_id).get(0);
                        quiz.get(chat_id).remove(current);
                        String question = current.getQuestion();
                        List<String>options = current.getOptions();
                        int correct_id = current.getCorrectId();
                        sendPoll(chat_id, "1) " + question, options, correct_id);
                        stats.putIfAbsent(chat_id, new Pair<>(0, System.currentTimeMillis()));
                        Message message = callbackQuery.getMessage();
                        editMessage(chat_id, message.getMessageId(), message.getText(), null);
                    } else if (queryData.equals("add")){
                        if (subjects.isEmpty())
                            return;
                        String sampleId = "BQACAgIAAxkBAAICb2HZrJpLAsqZRxMk_kJ4PupboJwvAAKNEQACEkXRSsxMo-neQlv9IwQ";
                        deleteMessage(chat_id, callbackQuery.getMessage().getMessageId());
                        sendTestSample(chat_id, translate("Отправьте тест в формате DOCX согласно прикрепленному шаблону", language.get(chat_id)), sampleId);
                        state.put(chat_id, "waiting4"+selectedSubject);
                    } else if (queryData.equals("remove")){
                        if (subjects.isEmpty())
                            return;
                        Connection connection;
                        if (connections.containsKey(chat_id))
                            connection = connections.get(chat_id);
                        else
                            connection = database.connect();
                        try {
                            Statement statement = connection.createStatement();
                            statement.execute(String.format("delete from subjects where name = '%s'", selectedSubject));
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                        String finalSelectedSubject = selectedSubject;
                        subjects.removeIf(element -> element.getCaption().equals(finalSelectedSubject));
                        editMessage(chat_id, callbackQuery.getMessage().getMessageId(), String.format(translate("Предмет <b>%s</b> удалён ✅", language.get(chat_id)), selectedSubject), null);
                        sendMessage(chat_id, translate("\uD83D\uDCDA Выберите предмет:", language.get(chat_id)), buildMarkup(subjects, 1));

                    } else if (queryData.equals("add2")){
                        if (subjects.isEmpty())
                            return;
                        editMessage(chat_id, callbackQuery.getMessage().getMessageId(), translate("✏️ Укажите называнию предмета:", language.get(chat_id)), null);
                        state.put(chat_id, "waiting4subject");
                    } else if (queryData.equals("reportError")){
                        if (subjects.isEmpty())
                            return;
                        Connection connection;
                        if (connections.containsKey(chat_id))
                            connection = connections.get(chat_id);
                        else {
                            connection = database.connect();
                            connections.put(chat_id, connection);
                        }
                        try {
                            Statement statement = connection.createStatement();
                            // ResultSet rs = statement.executeQuery("(select text, lang from questions where id = %s union select text, text from options where qid = %s and correct = true)".formatted(selectedSubject, selectedSubject));
                            ResultSet rs = statement.executeQuery("select text, lang from questions where id = " + selectedSubject);
                            if (rs.next()){
                                String wrongQ = rs.getString(1);
                                language.put(chat_id, rs.getString("lang"));
                                ResultSet rs2 = statement.executeQuery("select text from options where correct = true and qid = " + selectedSubject);
                                rs2.next();
                                String wrongA = rs2.getString(1);
                                List<Item> buttons = new ArrayList<>();
                                buttons.add(new Item(translate("Изменить вопрос", language.get(chat_id)), "changeQuestion+"+selectedSubject));
                                buttons.add(new Item(translate("Изменить ответ", language.get(chat_id)), "changeAnswer+"+selectedSubject));
                                sendMessage(chat_id, translate("<b>Вопрос: </b>", language.get(chat_id)) + wrongQ + translate("\n<b>Ответ: </b> ", language.get(chat_id)) + wrongA, buildMarkup(buttons, 1));
                            }
                        } catch (SQLException e) {
                            e.printStackTrace();
                        }
                    } else if (queryData.equals("changeQuestion")){
                        if (subjects.isEmpty())
                            return;
                        int messageId = callbackQuery.getMessage().getMessageId();
                        String messageText = callbackQuery.getMessage().getText();
                        editMessage(chat_id, messageId, messageText+translate("\n\n<i>Укажите корректный вопрос:</i>", language.get(chat_id)), null);
                        state.put(chat_id, "waiting4fixquestion+"+selectedSubject);
                    } else if (queryData.equals("changeAnswer")){
                        if (subjects.isEmpty())
                            return;
                        int messageId = callbackQuery.getMessage().getMessageId();
                        String messageText = callbackQuery.getMessage().getText();
                        editMessage(chat_id, messageId, messageText+translate("\n\n<i>Укажите корректный ответ:</i>", language.get(chat_id)), null);
                        state.put(chat_id, "waiting4fixanswer+"+selectedSubject);
                    } else if (queryData.equals("changeLang")){
                        callbackQuery = update.getCallbackQuery();
                        if (selectedSubject.equals("rus"))
                            editMessage(chat_id, callbackQuery.getMessage().getMessageId(), String.format(translate("<b>Функция изменение языка работы бота будет скоро добавлена!\nПросим извинения за неудобства. /start</b>", language.get(chat_id)), language.get(chat_id)), null);
                        else if (selectedSubject.equals("eng"))
                            editMessage(chat_id, callbackQuery.getMessage().getMessageId(), String.format(translate("<b>Changing language of the bot will be available soon.\nSorry for causing inconveniences. /start</b>", language.get(chat_id)), language.get(chat_id)), null);
                        else
                            editMessage(chat_id, callbackQuery.getMessage().getMessageId(), String.format(translate("<b>Тілді өзгерту жақын арада мүмкін болады.\nҚолайсыздықтар үшін кешірім сұраймыз. /start</b>", language.get(chat_id)), language.get(chat_id)), null);
                    } else if (queryData.equals("stats")){
                        if (subjects.isEmpty())
                            return;
                        LoadStats(callbackQuery, selectedSubject);
                    }
                } else {
                boolean isAdmin = false;
                try {
                    Connection connection;
                    if (connections.containsKey(chat_id))
                        connection = connections.get(chat_id);
                    else
                        connection = database.connect();
                    Statement statement = connection.createStatement();
                    ResultSet check = statement.executeQuery("select admin from users where user_id = " + chat_id);
                    if (check.next())
                        isAdmin = check.getBoolean("admin");
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                Message oldMessage = callbackQuery.getMessage();
                List<Item>buttons = new ArrayList<>();
                buttons.add(new Item(translate("Начать тестирование", language.get(chat_id)), "test+"+selectedSubject));
                if (isAdmin) {
                    buttons.add(new Item(translate("Добавить тест", language.get(chat_id)), "add+" + selectedSubject));
                    buttons.add(new Item(translate("Удалить предмет", language.get(chat_id)), "remove+" + selectedSubject));
                }
                buttons.add(new Item(translate("Статистика", language.get(chat_id)), "stats+"+selectedSubject));
                editMessage(oldMessage.getChatId(), oldMessage.getMessageId(), translate("<b>\uD83D\uDCDD Предмет:</b> ", language.get(chat_id)) + selectedSubject, buildMarkup(buttons, 1));
                lastSelected.put(chat_id, selectedSubject);
            }
        }
    }
    public void sendMessage(Long chat_id, String text, InlineKeyboardMarkup markup){
        SendMessage sendMessage = new SendMessage();
        sendMessage.setText(text);
        sendMessage.setChatId(chat_id.toString());
        sendMessage.setReplyMarkup(markup);
        sendMessage.setParseMode("HTML");
        sendMessage.setProtectContent(true);
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void sendMessage(Long chat_id, String text){
        SendMessage sendMessage = new SendMessage();
        sendMessage.setText(text);
        sendMessage.setParseMode("HTML");
        sendMessage.setChatId(chat_id.toString());
        sendMessage.setProtectContent(true);
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void sendSticker(Long chat_id, String sticker_id){
        SendSticker sendSticker = new SendSticker();
        sendSticker.setChatId(chat_id.toString());
        sendSticker.setProtectContent(true);
        sendSticker.setSticker(new InputFile(sticker_id));
        try {
            execute(sendSticker);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void sendTestSample(Long chat_id, String text, String file_id){
        SendDocument sendDocument = new SendDocument();
        sendDocument.setChatId(chat_id.toString());
        sendDocument.setParseMode("HTML");
        sendDocument.setProtectContent(true);
        sendDocument.setCaption(text);
        sendDocument.setDocument(new InputFile().setMedia(file_id));
        try {
            execute(sendDocument);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public InlineKeyboardMarkup buildMarkup(List<Item>buttons, int column){
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>>keyboard = new ArrayList<>();
        int i = 0;
        while(i < buttons.size()){
            int col = 0;
            List<InlineKeyboardButton>inlineButtons = new ArrayList<>();
            for (; i < buttons.size(); i++){
                Item button = buttons.get(i);
                InlineKeyboardButton currentButton = new InlineKeyboardButton();
                currentButton.setText(button.getCaption());
                currentButton.setCallbackData(button.getQueryData());
                inlineButtons.add(currentButton);
                if (++col == column) break;
            }
            keyboard.add(inlineButtons);
            i += col;
        }
        markup.setKeyboard(keyboard);
        return markup;
    }

    public void sendPoll(Long chat_id, String question, List<String> options, int correct_id){
        SendPoll sendPoll = new SendPoll();
        sendPoll.setChatId(chat_id.toString());
        sendPoll.setProtectContent(true);
        sendPoll.setQuestion(question.substring(0, question.indexOf('~')));
        sendPoll.setOptions(options);
        sendPoll.setCorrectOptionId(correct_id);
        sendPoll.setType("quiz");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>>report = new ArrayList<>();
        List<InlineKeyboardButton>reportBtn = new ArrayList<>();
        InlineKeyboardButton reportButton = new InlineKeyboardButton();
        reportButton.setText(translate("Сообщить об ошибке", language.get(chat_id)));
        reportButton.setCallbackData("reportError+"+question.substring(question.indexOf('~')+1));
        reportBtn.add(reportButton);
        report.add(reportBtn);
        markup.setKeyboard(report);
        sendPoll.setReplyMarkup(markup);
        try {
            Message message = execute(sendPoll);
            storage.putIfAbsent(message.getPoll().getId(), message);
            if (quizHistory.containsKey(chat_id))
                quizHistory.get(chat_id).add(message);
            else {
                List<Message>polls = new ArrayList<>();
                polls.add(message);
                quizHistory.put(chat_id, polls);
            }
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void editMessage(Long chat_id, int message_id, String new_text, InlineKeyboardMarkup markup){
        EditMessageText editMessageText = new EditMessageText();
        editMessageText.setChatId(chat_id.toString());
        editMessageText.setMessageId(message_id);
        editMessageText.setText(new_text);
        editMessageText.setParseMode("HTML");
        editMessageText.setReplyMarkup(markup);
        try {
            execute(editMessageText);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void deleteMessage(Long chat_id, int message_id){
        DeleteMessage deleteMessage = new DeleteMessage(chat_id.toString(), message_id);
        try {
            execute(deleteMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public List<Question> LoadTest(Long user_id, String subject, String lang){
        List<Question>test = new ArrayList<>();
        try {
            Connection connection;
            if (connections.containsKey(user_id))
                connection = connections.get(user_id);
            else
                connection = database.connect();
            Statement statement = connection.createStatement();
            int subject_id = 0;
            ResultSet rs = statement.executeQuery(String.format("select id from subjects where name = '%s' and lang = '%s'", subject, lang));
            if (rs.next())
                subject_id = rs.getInt(1);
            ResultSet questionsList = statement.executeQuery(String.format("select * from questions where subject_id = %d order by random() limit 15", subject_id));
            PreparedStatement preparedStatement = connection.prepareStatement("select * from options where qid = ?");
            while(questionsList.next()){
                long id = questionsList.getLong(1);
                String text = questionsList.getString(2);
                preparedStatement.setLong(1, id);
                ResultSet optionsList = preparedStatement.executeQuery();
                List<String>options = new ArrayList<>();
                while(optionsList.next())
                    options.add(optionsList.getString("text"));
                String correctQ = options.get(0);
                Collections.shuffle(options);
                test.add(new Question(text + "~" + id, options, options.indexOf(correctQ)));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        Collections.shuffle(test);
        return test;
    }

    public void LoadStats(CallbackQuery callbackQuery, String subject){
        Connection connection;
        Long user_id = callbackQuery.getFrom().getId();
        int message_id = callbackQuery.getMessage().getMessageId();
        if (connections.containsKey(user_id))
            connection = connections.get(user_id);
        else {
            connection = database.connect();
            connections.put(user_id, connection);
        }
        try {
            Statement statement = connection.createStatement();
            ResultSet userInfo = statement.executeQuery("select id from users where user_id = " + user_id);
            if (userInfo.next()){
                int uid = userInfo.getInt(1);
                ResultSet subjectInfo = statement.executeQuery(String.format("select id from subjects where name = '%s'", subject));
                if (subjectInfo.next()){
                    int sid = subjectInfo.getInt(1);
                    ResultSet statsInfo = statement.executeQuery(String.format("select * from stats where uid = %d and sid = %d", uid, sid));
                    if (statsInfo.next()){
                        Date date = new SimpleDateFormat("yyyy-MM-dd").parse(statsInfo.getString("date"));
                        String data = DateFormat.getDateInstance(SimpleDateFormat.LONG, new Locale("ru")).format(date);
                        editMessage(user_id, message_id, translate("<b>\uD83D\uDCCA Статистика</b>\n\n<b>\uD83D\uDCD4 Предмет: </b>", language.get(user_id)) + subject + translate("\n\n<b>\uD83D\uDCCC Всего:</b> ", language.get(user_id)) + statsInfo.getInt("points") + translate(" баллов", language.get(user_id)) + translate("\n\n<b>\uD83D\uDCDC Количество пройденных тестов:</b> ", language.get(user_id)) + statsInfo.getInt("no") + translate("\n\n<b>\uD83D\uDCC5 Дата последнего тестирование: </b>", language.get(user_id)) + data, null);
                    } else {
                        editMessage(user_id, message_id, translate("Статистика не доступна!\nСперва попробуйте пройти тестирование.", language.get(user_id)), null);
                    }
                }
            }
        } catch (SQLException | ParseException e) {
            e.printStackTrace();
        }
    }

    public String translate(String text, String lang){
/*        if (lang != null && lang.equals("kaz"))
            text = kazakhTranslations.get(text);
          if (lang.equals("eng"))
            text = russianTranslations.get(text);
 */
        return text;
    }

    private final Map<String, String> kazakhTranslations = Stream.of(new String[][] {
            {"Subject test <b>%s</b> was added ✅", "<b>%s</b> пәнінен тест жүктелді ✅"}, // пример
            {"Start the bot", "Ботты бастау"},
            {"My progress", "Менің прогрессім"},
            {"Show rating", "Рейтингті көрсету"},
            {"Change language", "Тілді ауыстыру"},
            {"<b>Выбранный язык: </b>%s\n\n<i>Просим перезапустить бот: /restart</i>", "<b>Выбранный язык: </b>%s\n\n<i>Ботты келесі команда арқылы қайта жүкте қажет: /restart</i>"},
            {"Cancel subscription", "Жазылымнан бастарту"},
            {"📝 Select subject:", "📝 Пәнді таңдаңыз:"},
            {"Add subject", "Жаңа пән ашу"},
            {"Remove subject", "Пәнді жояу"},
            {"<b>\uD83D\uDCDD Subject:</b> ", "<b>\uD83D\uDCDD Пән:</b> "},
            {"Subject <b>%s</b> was added", "<b>%s</b> пәні ашылды"},
            {"Subject <b>%s</b> was removed", "<b>%s</b> пәні жойылды"},
            {"Please, contact @admin to purchase subscription", "Жазылымды алу үшін @admin ге жазыңыз"},
            {"Your report is accepted and will be considered by moderators ✅", "Сіздің сұранысыңыз модераторларға жіберілді ✅"},
            {"<b>📊 Result:</b>", "<b>📊 Нәтиже:</b>"},
            {" out of ", "/"},
            {"\n<b>⏱️ Time taken: </b>", "\n<b>⏱️ Өткен уақыт: </b>"},
            {" min. ", " мин."},
            {" sec. ", " сек."},
            {"Please, send the test in DOCX format using the attached sample", "Тестті жоғарыдағы үлгіге сүйене отырып DOCX форматында жіберіңіз"},
            {"✏️ Enter subject name:", "✏️ Пәннің атауын еңгізіңіз:"},
            {"Change question", "Сұрақты түзету"},
            {"Change answer", "Жауапты түзету"},
            {"<b>Question: </b>", "<b>Сұрақ: </b>"},
            {"\n<b>Answer: </b>", "\n<b>Жауап: </b>"},
            {"\n\n<i>Enter correct question:</i>", "\n\n<i>Дұрыс сұрақты еңгізіңіз:</i>"},
            {"\n\n<i>Enter correct answer for the question:</i>", "\n\n<i>Дұрыс жауапты еңгізіңіз:</i>"},
            {"Start the test", "Тестілеуді бастау"},
            {"Tests on <b>%s</b> were not found.\nContact your teacher", "<b>%s</b> пәні бойынша тесттер табылмады.\nАдминистраторға жазуыңызды сұраймыз."},
            {"Add test", "Тест қосу"},
            {"Statistics", "Статистика"},
            {"Report a mistake", "Аппеляция"},
    }).collect(Collectors.toMap(data -> data[0], data -> data[1]));
}
