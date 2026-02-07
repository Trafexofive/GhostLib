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
                // LDLib2's ModularUI often checks for IDataProvider to decide how to sync.
                // A simple supplier might not auto-sync unless the UI container ticks and updates it.
                return new ISubscription() {
                    @Override
                    public void unsubscribe() {}
                };
            }

            @Override
            public T getValue() {
                return supplier.get();
            }
            
            public boolean isChanged(T lastValue) {
                T newValue = getValue();
                if (newValue == null) return lastValue != null;
                return !newValue.equals(lastValue);
            }
        };
    }
}