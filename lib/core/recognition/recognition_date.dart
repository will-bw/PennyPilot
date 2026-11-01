/// Resolves everyday date expressions without requiring form-like precision.
class RecognitionDate {
  static ({
    DateTime? date,
    String precision,
    List<String> assumptions,
    String? problem,
  })
  resolve({
    required String context,
    required String inputBeforeAmount,
    required DateTime? modelDate,
    required DateTime now,
  }) {
    final assumptions = <String>[];
    final today = DateTime(now.year, now.month, now.day);
    final matches = _dates(context, today);
    // A date introducing several comma-separated records continues to apply
    // until the user names another date.
    final candidates = matches.isEmpty
        ? _dates(inputBeforeAmount, today)
        : matches;
    final selected = candidates.lastOrNull;
    if (selected?.problem != null) {
      return (
        date: null,
        precision: 'day',
        assumptions: assumptions,
        problem: selected!.problem,
      );
    }
    DateTime? day = selected?.date;
    if (selected?.assumption != null) assumptions.add(selected!.assumption!);
    final vague = RegExp(
      r'[一二三四五六七八九十廿]+\s*月|[一二三四五六七八九十廿]+[日号]|[一二三四五六七八九十]+天前|国庆|春节|中秋|周末|上月|上个周末|上上周|半个月前|前几天|前些天|几天前|几个月前|前阵子|前段时间|之前|以前|上次|上回|那天|当时|最近|近日|早些时候|(?:上|本|这)个?月|去年|前年|(?:上|本|这)(?:周|星期|礼拜)(?![一二三四五六日天])',
    );
    if (day == null && vague.hasMatch(context)) {
      final actual = modelDate?.toLocal();
      day = actual == null
          ? today
          : DateTime(actual.year, actual.month, actual.day);
      assumptions.add(
        actual == null
            ? '原文未指定具体日期，暂按今天记录'
            : '日期按“${vague.firstMatch(context)![0]}”推断',
      );
    }
    if (day == null) {
      // Without a date cue, a model-supplied historical day has no basis.
      day = today;
      assumptions.add('未提日期，按今天记录');
    }
    if (day.isAfter(today)) {
      return (
        date: null,
        precision: 'day',
        assumptions: assumptions,
        problem: '描述的日期还在未来，暂不记入已发生账目。',
      );
    }
    final clock = _clock(context);
    if (clock != null) {
      var hour = clock.hour;
      final period =
          clock.period ?? (RegExp(r'昨晚|今晚').hasMatch(context) ? '晚上' : null);
      if (period != null &&
          RegExp(r'下午|傍晚|晚上|晚间|夜里|夜晚').hasMatch(period) &&
          hour < 12) {
        hour += 12;
      }
      if (period == '凌晨' && hour == 12) hour = 0;
      if (period == '中午' && hour < 11) hour += 12;
      if (hour > 23 || clock.minute > 59) {
        return (
          date: null,
          precision: 'exact',
          assumptions: assumptions,
          problem: '时间超出有效范围，请核对小时和分钟。',
        );
      }
      final date = DateTime(day.year, day.month, day.day, hour, clock.minute);
      if (clock.approximate) {
        assumptions.add(
          '“${clock.quote}”按 $hour:${clock.minute.toString().padLeft(2, '0')} 左右记录',
        );
      }
      if (date.isAfter(now.add(const Duration(minutes: 5)))) {
        return (
          date: null,
          precision: 'exact',
          assumptions: assumptions,
          problem: '描述的时间还在未来，请核对发生时间。',
        );
      }
      return (
        date: date,
        precision: clock.approximate ? 'approximate' : 'exact',
        assumptions: assumptions,
        problem: null,
      );
    }
    final actual = modelDate?.toLocal();
    // Exact model time is retained only when the input contains a clock. A
    // calendar day or broad period does not need invented minutes.
    if (selected == null &&
        !vague.hasMatch(context) &&
        actual != null &&
        (actual.year != today.year ||
            actual.month != today.month ||
            actual.day != today.day)) {
      assumptions.add('模型日期已按未提日期的规则改为今天');
    }
    return (
      date: selected == null && !vague.hasMatch(context) ? now : day,
      precision: selected == null && !vague.hasMatch(context)
          ? 'user_entry_time'
          : 'day',
      assumptions: assumptions,
      problem: null,
    );
  }

  static List<({DateTime? date, String? assumption, String? problem})> _dates(
    String text,
    DateTime today,
  ) {
    final result =
        <({int start, DateTime? date, String? assumption, String? problem})>[];
    final occupied = <({int start, int end})>[];
    void add(
      RegExp pattern,
      ({DateTime? date, String? assumption, String? problem}) Function(Match)
      resolve,
    ) {
      for (final m in pattern.allMatches(text)) {
        if (occupied.any((r) => m.start < r.end && m.end > r.start)) continue;
        final value = resolve(m);
        result.add((
          start: m.start,
          date: value.date,
          assumption: value.assumption,
          problem: value.problem,
        ));
        occupied.add((start: m.start, end: m.end));
      }
    }

    DateTime? calendar(int year, int month, int day) {
      if (year < 2000 ||
          year > 2100 ||
          month < 1 ||
          month > 12 ||
          day < 1 ||
          day > DateTime(year, month + 1, 0).day) {
        return null;
      }
      return DateTime(year, month, day);
    }

    ({DateTime? date, String? assumption, String? problem}) dateResult(
      DateTime? date, [
      String? assumption,
    ]) => (
      date: date,
      assumption: assumption,
      problem: date == null ? '日期无效，请核对月份和日号。' : null,
    );
    add(
      RegExp(r'(20\d{2})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]?'),
      (m) => dateResult(
        calendar(int.parse(m[1]!), int.parse(m[2]!), int.parse(m[3]!)),
      ),
    );
    add(
      RegExp(r'(?<!\d)(20\d{2})[-/](\d{1,2})[-/](\d{1,2})(?!\d)'),
      (m) => dateResult(
        calendar(int.parse(m[1]!), int.parse(m[2]!), int.parse(m[3]!)),
      ),
    );
    add(RegExp(r'(?:(去年|前年|今年)\s*)?(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]?'), (m) {
      var year =
          today.year -
          (m[1] == '去年'
              ? 1
              : m[1] == '前年'
              ? 2
              : 0);
      var date = calendar(year, int.parse(m[2]!), int.parse(m[3]!));
      if (m[1] == null && int.parse(m[2]!) == 2 && int.parse(m[3]!) == 29) {
        while (date == null && year > 2000) {
          date = calendar(--year, 2, 29);
        }
      }
      if (m[1] == null && date != null && date.isAfter(today)) {
        date = calendar(--year, int.parse(m[2]!), int.parse(m[3]!));
      }
      return dateResult(date, m[1] == null ? '未提年份，按最近已发生的 $year 年记录' : null);
    });
    add(RegExp(r'(?<![\d/])(\d{1,2})/(\d{1,2})(?![\d/])'), (m) {
      var year = today.year;
      var date = calendar(year, int.parse(m[1]!), int.parse(m[2]!));
      if (date != null && date.isAfter(today)) {
        date = calendar(--year, int.parse(m[1]!), int.parse(m[2]!));
      }
      return dateResult(date, '未提年份，按最近已发生的 $year 年记录');
    });
    add(RegExp(r'大前天|前天|昨天|昨日|昨晚|今天|今日|今晚|大后天|后天|明天'), (m) {
      final offset = switch (m[0]) {
        '大前天' => -3,
        '前天' => -2,
        '昨天' || '昨日' || '昨晚' => -1,
        '大后天' => 3,
        '后天' => 2,
        '明天' => 1,
        _ => 0,
      };
      return dateResult(DateTime(today.year, today.month, today.day + offset));
    });
    add(RegExp(r'(上上|上|本|这)?(?:周|星期|礼拜)([一二三四五六日天])'), (m) {
      final weekday = '一二三四五六日'.indexOf(m[2] == '天' ? '日' : m[2]!) + 1;
      final weeks = m[1] == '上上'
          ? 2
          : m[1] == '上'
          ? 1
          : 0;
      var day = DateTime(
        today.year,
        today.month,
        today.day - today.weekday + weekday - weeks * 7,
      );
      if (m[1] == null && day.isAfter(today)) {
        day = DateTime(day.year, day.month, day.day - 7);
      }
      return dateResult(day, m[1] == null ? '“${m[0]}”按最近已发生的星期记录' : null);
    });
    add(
      RegExp(r'(?<!\d)(\d{1,3})天前'),
      (m) => dateResult(
        DateTime(today.year, today.month, today.day - int.parse(m[1]!)),
      ),
    );
    result.sort((a, b) => a.start.compareTo(b.start));
    return result
        .map(
          (r) => (date: r.date, assumption: r.assumption, problem: r.problem),
        )
        .toList();
  }

  static ({
    int hour,
    int minute,
    String? period,
    bool approximate,
    String quote,
  })?
  _clock(String text) {
    final amounts = RegExp(
      r'(?:[零〇一二两三四五六七八九十百千万]+|\d+)\s*点\s*[零〇一二两三四五六七八九\d]+\s*(?:元|块|毛|角)',
    ).allMatches(text).toList();
    final m =
        RegExp(
          r'(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|晚间|夜里|夜晚)?\s*([零一二两三四五六七八九十]{1,3}|[0-2]?\d)\s*(?:点|[:：])\s*(半|一刻|三刻|\d{1,2}\s*分?)?(过|多|左右)?',
        ).allMatches(text).where((clock) {
          // Spoken decimal amounts also use “点”; they provide no clock evidence.
          return !amounts.any(
            (amount) => clock.start < amount.end && clock.end > amount.start,
          );
        }).lastOrNull;
    if (m == null) return null;
    final minute = switch (m[3]?.trim()) {
      '半' => 30,
      '一刻' => 15,
      '三刻' => 45,
      final value? => int.tryParse(value.replaceAll('分', '').trim()) ?? 0,
      _ => 0,
    };
    return (
      hour: int.tryParse(m[2]!) ?? _chineseHour(m[2]!),
      minute: minute,
      period: m[1],
      approximate: m[4] != null,
      quote: m[0]!.trim(),
    );
  }

  static int _chineseHour(String value) {
    const digits = {
      '零': 0,
      '一': 1,
      '二': 2,
      '两': 2,
      '三': 3,
      '四': 4,
      '五': 5,
      '六': 6,
      '七': 7,
      '八': 8,
      '九': 9,
    };
    if (!value.contains('十')) return digits[value]!;
    final parts = value.split('十');
    return (parts.first.isEmpty ? 1 : digits[parts.first]!) * 10 +
        (parts.last.isEmpty ? 0 : digits[parts.last]!);
  }
}
