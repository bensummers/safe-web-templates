package org.haplo.template.html;

public class Template {
    private String name;
    private NodeList nodes;
    private int numberOfRememberedViews;

    protected Template(String name, NodeList nodes, int numberOfRememberedViews) {
        this.name = name;
        this.nodes = nodes;
        this.numberOfRememberedViews = numberOfRememberedViews;
    }

    public String getName() {
        return this.name;
    }

    public void render(StringBuilder builder, Driver driver) throws RenderException {
        driver.setupForRender(this);
        this.nodes.render(builder, driver, driver.getRootView(), Context.TEXT);
    }

    public void renderAsIncludedTemplate(StringBuilder builder, Driver driver, Object view, Context context) throws RenderException {
        driver.setupForRender(this);
        this.nodes.render(builder, driver, view, context);
    }

    public DeferredRender deferredRender(Driver driver) throws RenderException {
        driver.setupForRender(this);
        return (builder, context) -> {
            if(context != Context.TEXT) {
                throw new RenderException(driver, "Can't deferred render into this context");
            }
            this.nodes.render(builder, driver, driver.getRootView(), context);
        };
    }

    public String renderString(Driver driver) throws RenderException {
        StringBuilder builder = new StringBuilder();
        render(builder, driver);
        return builder.toString();
    }

    public String dump() {
        StringBuilder builder = new StringBuilder();
        nodes.dumpToBuilder(builder, "");
        return builder.toString();
    }

    protected int getNumberOfRememberedViews() {
        return this.numberOfRememberedViews;
    }
}
