/*
 *     Copyright (C) <2021>  <Junwen Chen>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.ordinate.engine.function.time;


import io.ordinate.engine.builder.EngineConfiguration;
import io.ordinate.engine.function.*;
import io.ordinate.engine.record.Record;

import java.time.Duration;
import java.util.List;

public class DayDiffFunctionFactory implements FunctionFactory {
    @Override
    public String getSignature() {
        return "DATEDIFF(date,date):long";
    }

    @Override
    public Function newInstance(List<Function> args, EngineConfiguration configuration) {
        return new DateDiffFunction(args);
    }

    class DateDiffFunction extends LongFunction {
        List<Function> args;
        boolean isNull;

        public DateDiffFunction(List<Function> args) {
            this.args = args;
        }

        @Override
        public List<Function> getArgs() {
            return args;
        }

        @Override
        public long getLong(Record rec) {
            Function one = args.get(0);
            Function two = args.get(1);

            long date1 = one.getDate(rec);
            long date2 = two.getDate(rec);

            isNull = one.isNull(rec) || two.isNull(rec);
            if (isNull) return 0;
            return Duration.ofMillis(date1 - date2).toDays();
        }

    }

    ;
}
