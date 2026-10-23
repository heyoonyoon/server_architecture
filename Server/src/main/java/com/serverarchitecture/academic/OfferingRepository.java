package com.serverarchitecture.academic;

import com.serverarchitecture.DataClient;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.data.api.Item;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class OfferingRepository {
    private final DataClient data;

    public OfferingRepository(DataClient data) {
        this.data = data;
    }

    public Optional<Offering> findById(int offeringId) {
        return data.read(DataCollections.OFFERINGS, String.valueOf(offeringId)).map(OfferingRepository::toOffering);
    }

    public List<Offering> findBySemester(String semester) {
        return data.readWhere(DataCollections.OFFERINGS, "semester", semester).stream()
                .map(OfferingRepository::toOffering)
                .toList();
    }

    public List<Offering> findByCourse(String courseId) {
        return data.readWhere(DataCollections.OFFERINGS, "course_id", courseId).stream()
                .map(OfferingRepository::toOffering)
                .toList();
    }

    private static Offering toOffering(Item item) {
        Map<String, String> fields = item.getFieldsMap();
        return new Offering(Integer.parseInt(item.getId()),
                fields.getOrDefault("course_id", ""),
                fields.getOrDefault("semester", ""),
                fields.getOrDefault("section", ""),
                fields.getOrDefault("professor_id", ""),
                Integer.parseInt(fields.getOrDefault("capacity", "0")),
                fields.getOrDefault("room", ""),
                fields.getOrDefault("times", ""));
    }
}
