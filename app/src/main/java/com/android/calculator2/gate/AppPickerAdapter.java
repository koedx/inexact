/*
 * Copyright (C) 2026 The Android Open Source Project / InExact fork
 *
 * Purpose: RecyclerView adapter for the app picker list — label + lazily-bound icon, click to launch
 *          (spec addendum 2026-11-06-01 §3.5). No gate/credential logic.
 * Created:  2026-11-06 09:27:32 UTC
 * Modified: 2026-11-06 09:27:32 UTC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */

package com.android.calculator2.gate;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.calculator2.R;

import java.util.ArrayList;
import java.util.List;

/** Flat, label-sorted app list. Icons bind asynchronously via {@link IconCache}. */
final class AppPickerAdapter extends RecyclerView.Adapter<AppPickerAdapter.Holder> {

    interface OnAppClick {
        void onAppClick(AppEntry entry);
    }

    private final IconCache icons;
    private final OnAppClick clickListener;
    private final List<AppEntry> items = new ArrayList<>();

    AppPickerAdapter(IconCache icons, OnAppClick clickListener) {
        this.icons = icons;
        this.clickListener = clickListener;
        setHasStableIds(true);
    }

    void submit(List<AppEntry> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();   // simple; swap for DiffUtil once search churn is measured
    }

    @Override public long getItemId(int position) {
        return items.get(position).key().hashCode();
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.launcher_row, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder h, int position) {
        final AppEntry entry = items.get(position);
        h.label.setText(entry.label);
        h.boundKey = entry.key();
        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clickListener.onAppClick(entry); }
        });

        final Bitmap cached = icons.peek(entry.key());
        if (cached != null) {
            h.icon.setImageBitmap(cached);
        } else {
            h.icon.setImageDrawable(null);   // clear a recycled icon so no wrong image flashes
            icons.request(entry.key(), entry.component(), new IconCache.Callback() {
                @Override public void onIcon(String key, Bitmap bitmap) {
                    if (key.equals(h.boundKey)) {   // holder not recycled to another app
                        h.icon.setImageBitmap(bitmap);
                    }
                }
            });
        }
    }

    @Override public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        String boundKey;

        Holder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            label = itemView.findViewById(R.id.app_label);
        }
    }
}
