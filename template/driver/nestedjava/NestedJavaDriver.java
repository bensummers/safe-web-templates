package template.driver.nestedjava;

import java.util.Map;
import java.util.Arrays;

import template.Driver;
import template.Template;
import template.Context;

class NestedJavaDriver extends Driver {
    private Object rootView;
    private Map<String,Template> inclusions;

    public NestedJavaDriver(Object view, Map<String,Template> inclusions) {
        this.rootView = view;
        this.inclusions = inclusions;
    }

    public Driver driverWithNewRoot(Object rootView) {
        return new NestedJavaDriver(rootView, this.inclusions);
    }

    public Object getRootView() {
        return this.rootView;
    }

    @SuppressWarnings("unchecked")
    public Object getValueFromView(Object view, String[] path) {
        Object o = view;
        for(String key : path) {
            if(o == null) { return null; }
            if(o instanceof Map) {
                o = ((Map<String,Object>)o).get(key);
            } else {
                o = null; // can't traverse
            }
        }
        return o;
    }

    public String valueToStringRepresentation(Object value) {
        return (value == null) ? null : value.toString();
    }

    public Iterable<Object> valueToIterableViewList(Object value) {
        if(!(value instanceof Object[])) { return null; }
        return Arrays.asList((Object[])value);
    }

    @SuppressWarnings("unchecked")
    public void iterateOverValueAsDictionary(Object value, DictionaryIterator iterator) {
        if(!(value instanceof Map)) { return; }
        for(Map.Entry<String,Object> entry : ((Map<String,Object>)value).entrySet()) {
            iterator.entry(entry.getKey(), entry.getValue());
        }
    }

    // Uses default valueIsTruthy() implementation

    public void renderInclusion(String inclusionName, StringBuilder builder, Context context) {
        // TODO: Error if inclusion not found?
        if(this.inclusions == null) { return; }
        Template template = this.inclusions.get(inclusionName);
        if(template != null) {
            template.renderAsInclusion(builder, this, this.getRootView(), context);
        }
    }
}
