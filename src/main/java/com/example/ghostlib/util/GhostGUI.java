package com.example.ghostlib.util;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataProvider;
import com.lowdragmc.lowdraglib2.syncdata.ISubscription;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class GhostGUI {

    public static <T> IDataProvider<T> supplier(Supplier<T> supplier) {
        return new IDataProvider<T>() {
            @Override
            public ISubscription registerListener(Consumer<T> listener) {
                return new ISubscription() {
                    @Override
                    public void unsubscribe() {}
                };
            }

            @Override
            public T getValue() {
                return supplier.get();
            }
        };
    }
}