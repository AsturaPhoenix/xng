package io.tqi.ekg;

import java.io.Serializable;

import lombok.Value;

@Value
public class Identifier implements Serializable {
    private static final long serialVersionUID = -3465668008896269028L;
    String value;

    @Override
    public String toString() {
        return value;
    }
}
