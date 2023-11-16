package com.balak1ng;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class LinesToGroupsSplitter {
    private static final Logger logger = Logger.getLogger(LinesToGroupsSplitter.class.toString());
    private static final String LINE_SEP = System.lineSeparator();
    private static final String OUTPUT = "result.txt";

    /**
     * Максимальное количество колонок в валидной строке.
     * Необходима для вычисления уникального индекса в методе findGroupsForEachString.
     */
    private static int maxColumns;

    /**
     * Хранилище всех валидных строк из файла.
     */
    private static List<List<String>> rowsStorage;

    /**
     * Храним все слова, которые встретились среди всех строк более 1 раза.
     */
    private static Map<String, Set<Integer>> duplicatedWordsAndTheirPositionsInRows;

    public static void main(String[] args) {
        long start = System.currentTimeMillis();

        maxColumns = 0;
        rowsStorage = new ArrayList<>();
        duplicatedWordsAndTheirPositionsInRows = new HashMap<>();

        try {
            readFile(args[0]);
        } catch (Exception exception) {
            logger.info("Error while reading file.");
            return;
        }

        Map<String, Set<Integer>> conditions = formConditions();

        Map<Integer, List<Integer>> mapStringToGroups = findGroupsForEachString(conditions);

        Map<Integer, Set<Integer>> groups = createGroups(mapStringToGroups);

        mergeGroups(groups, mapStringToGroups);

        try {
            writeInfoToFile(groups, mapStringToGroups, start);
        } catch (IOException exception) {
            logger.info("Error while writing to file.");
        }
    }

    /**
     * Перевод количества байт памяти в легко-читаемый формат отображения.
     */
    public static String formatSize(long v) {
        if (v < 1024) return v + " B";
        int z = (63 - Long.numberOfLeadingZeros(v)) / 10;
        return String.format("%.1f %sB", (double) v / (1L << (z * 10)), " KMGTPE".charAt(z));
    }

    /**
     * Проверить валидность слова, корректный формат: "12345" = "^\d+$".
     */
    private static boolean isWordValid(String str) {
        for (char letter : str.toCharArray()) {
            if (letter < '0' || letter > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Проверить валидность строки, т.е. каждое слово валидно.
     */
    private static boolean isRowValid(String row) {
        String[] words = row.split(";");
        for (String word : words) {
            String temp = word.substring(1, word.length() - 1);
            if (!isWordValid(temp)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Прочитать все строки из файла и сохранить результат анализа в нужные структуры данных.
     * Каждая валидная строка целиком (разбитая на слова) добавляется в rowsStorage.
     * Слово добавляется в duplicatedWordsAndTheirPositionsInRows, если встретилось несколько раз среди всех строк.
     * Касается всех непустых слов.
     */
    private static void readFile(String path) throws IOException {
        String nextLine;

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            final Map<String, Integer> validWordFrequency = new HashMap<>();

            while ((nextLine = reader.readLine()) != null) {
                if (!isRowValid(nextLine)) {
                    continue;
                }

                rowsStorage.add(new ArrayList<>());

                String[] words = nextLine.split(";");
                maxColumns = Math.max(maxColumns, words.length);
                for (String word : words) {
                    rowsStorage.get(rowsStorage.size() - 1).add(word);

                    String temp = word.substring(1, word.length() - 1);
                    if (!temp.isEmpty()) {
                        validWordFrequency.put(word, validWordFrequency.getOrDefault(word, 0) + 1);
                        if (validWordFrequency.get(word) > 1) {
                            duplicatedWordsAndTheirPositionsInRows.put(word, new HashSet<>());
                        }
                    }
                }
            }
        }
    }

    /**
     * Если слово, многократно встречающееся в файле, также несколько раз встречается на определенной позиции
     * в разных строках - это становится условием для создания новой группы (для объединения строк).
     * Под условием объединения подразумевается слово на определенной позиции.
     */
    private static Map<String, Set<Integer>> formConditions() {
        Map<String, Set<Integer>> conditions = new HashMap<>();

        for (List<String> row : rowsStorage) {
            for (int i = 0; i < row.size(); i++) {
                String word = row.get(i);
                if (duplicatedWordsAndTheirPositionsInRows.containsKey(word)
                        && (!duplicatedWordsAndTheirPositionsInRows.get(word).add(i))) {
                    conditions.computeIfAbsent(word, x -> new HashSet<>()).add(i);
                }
            }
        }

        return conditions;
    }


    /**
     * На основании вычисленных условий сопоставим каждой строке список подходящих ей групп.
     * Каждому слову сопоставим уникальный идентификатор.
     * При этом какое-то слово может являться объединяющим ключём для разных пар строк в разных столбцах,
     * то есть идентификатор теряет уникальность - придумаем своё правило вычисления generatedUniqueGroupId.
     * generatedUniqueGroupId = номер условия * (maxColumns + 1) + позиция слова в столбце.
     * Таким образом пересечений не будет (подобие кеша, матрицы).
     */
    private static Map<Integer, List<Integer>> findGroupsForEachString(Map<String, Set<Integer>> conditions) {
        Map<String, Integer> uniqueStringConditionIndex = new HashMap<>();

        int k = 0;
        for (Map.Entry<String, Set<Integer>> entry : conditions.entrySet()) {
            uniqueStringConditionIndex.put(entry.getKey(), k);
            k++;
        }

        Map<Integer, List<Integer>> mapStringToGroupIds = new HashMap<>();

        for (int j = 0; j < rowsStorage.size(); j++) {
            for (int i = 0; i < rowsStorage.get(j).size(); i++) {
                String word = rowsStorage.get(j).get(i);
                if (conditions.containsKey(word) && conditions.get(word).contains(i)) {
                    int generatedUniqueGroupId = uniqueStringConditionIndex.get(word) * (maxColumns + 1) + i;
                    mapStringToGroupIds.computeIfAbsent(j, x -> new ArrayList<>()).add(generatedUniqueGroupId);
                }
            }
        }

        return mapStringToGroupIds;
    }

    /**
     * Если строка принадлежит сразу нескольким группам - эти группы нужно объединить.
     * Складируем в список groupsToMerge все пары таких групп. Далее, объединяем группы между собой.
     * Если одна группа поглотила вторую - выставим ей родителя в parentGroup и удалим её из groups.
     * Таким образом решаем проблему ассоциативности.
     */
    private static void mergeGroups(Map<Integer, Set<Integer>> groups, Map<Integer, List<Integer>> mapStringIdToGroupIds) {
        List<int[]> groupsToMerge = new ArrayList<>();

        for (Map.Entry<Integer, List<Integer>> entry : mapStringIdToGroupIds.entrySet()) {
            for (int i = 1; i < entry.getValue().size(); i++) {
                int lastIndex = entry.getValue().size() - 1;
                groupsToMerge.add(new int[]{entry.getValue().get(lastIndex), entry.getValue().get(lastIndex - 1)});
            }
        }

        Map<Integer, Integer> parentGroup = new HashMap<>();

        for (int[] mergeGroups : groupsToMerge) {
            int firstParent = mergeGroups[0];
            if (!groups.containsKey(firstParent)) {
                firstParent = parentGroup.get(firstParent);
            }
            int secondParent = mergeGroups[1];
            if (!groups.containsKey(secondParent)) {
                secondParent = parentGroup.get(secondParent);
            }
            groups.get(firstParent).addAll(groups.get(secondParent));
            groups.remove(secondParent);
            parentGroup.put(secondParent, firstParent);
        }
    }


    /**
     * Теперь делаем обратный маппинг - номеру группы сопоставляем список строк.
     */
    private static Map<Integer, Set<Integer>> createGroups(Map<Integer, List<Integer>> mapStringToGroups) {
        Map<Integer, Set<Integer>> groups = new HashMap<>();

        for (Map.Entry<Integer, List<Integer>> entry : mapStringToGroups.entrySet()) {
            for (Integer groupId : entry.getValue()) {
                groups.computeIfAbsent(groupId, x -> new HashSet<>()).add(entry.getKey());
            }
        }

        return groups;
    }

    /**
     * Запишем в файл все сформированные группы строк и дополнительную информацию о работе приложения.
     */
    private static void writeInfoToFile(Map<Integer, Set<Integer>> groups,
                                        Map<Integer, List<Integer>> mapStringToGroups,
                                        long start) throws IOException {

        int groupId = 1;

        // Сортируем группы по количеству строк.
        groups = groups.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT))) {
            writer.write("There are " + groups.size() + " groups with 2 elements and more." + LINE_SEP + LINE_SEP);

            for (Map.Entry<Integer, Set<Integer>> entry : groups.entrySet()) {

                writer.write("Group #" + groupId + " (consists of " + entry.getValue().size() + " elements)" + LINE_SEP);
                for (Integer index : entry.getValue()) {
                    writer.write(rowsStorage.get(index).toString() + LINE_SEP);
                }
                groupId++;
                writer.write(LINE_SEP);
            }

            for (int i = 0; i < rowsStorage.size(); i++) {
                if (!mapStringToGroups.containsKey(i)) {
                    writer.write("Group #" + groupId + " (consists of 1 element)" + LINE_SEP);
                    writer.write(rowsStorage.get(i).toString() + LINE_SEP);
                    writer.write(LINE_SEP);
                    groupId++;
                }
            }

            writer.write("All " + (groupId - 1) + " groups created." + LINE_SEP + LINE_SEP);

            long end = System.currentTimeMillis();
            long heapSize = Runtime.getRuntime().totalMemory();
            long heapFreeSize = Runtime.getRuntime().freeMemory();

            writer.write("Total time: " + (end - start) + " millis" + LINE_SEP);
            writer.write("Total memory: " + formatSize(heapSize - heapFreeSize) + LINE_SEP);
        }

    }
}
