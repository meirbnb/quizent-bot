public class Item {
    private String caption;
    private String queryData;

    Item(String caption, String queryData){
        this.caption = caption;
        this.queryData = queryData;
    }

    Item(String caption) {
        this.caption = this.queryData = caption;
    }

    public String getCaption() {
        return caption;
    }

    public String getQueryData() {
        return queryData;
    }
}
