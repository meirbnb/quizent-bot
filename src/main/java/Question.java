import java.util.ArrayList;
import java.util.List;

public class Question {
    private String question;
    private List<String> options = new ArrayList<>();
    private int correct_id;

    Question(String question, List<String>options, int correct_id){
        this.question = question;
        this.options = options;
        for (String option : options)
            if (!this.options.contains(option))
                this.options.add(option);
        this.correct_id = correct_id;
    }

    Question(String question, int correct_id){
        this.question = question;
        this.correct_id = correct_id;
    }

    public String getQuestion() {
        return question;
    }

    public List<String> getOptions() {
        return options;
    }

    public void setOptions(List<String>options){
        for (String option : options)
            if (!this.options.contains(option))
                this.options.add(option);
    }

    public int getCorrectId() {
        return correct_id;
    }
}
